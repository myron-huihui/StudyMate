package com.studymate.kb.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.common.entity.KbIngestJob;
import com.studymate.common.entity.LearningMaterial;
import com.studymate.dal.mapper.KbIngestJobMapper;
import com.studymate.dal.mapper.LearningMaterialMapper;
import com.studymate.dal.redis.RedisService;
import com.studymate.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 知识库六阶段离线流水线：Sanitize → Dedup → Chunk → Enhance → Embed → Store。
 * 每阶段结束即把产物与阶段游标写回任务表，进程重启后可从未完成的阶段继续。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbIngestService {

    private static final String STAGE_SANITIZE = "SANITIZE";
    private static final String STAGE_DEDUP = "DEDUP";
    private static final String STAGE_CHUNK = "CHUNK";
    private static final String STAGE_ENHANCE = "ENHANCE";
    private static final String STAGE_EMBED = "EMBED";
    private static final String STAGE_STORE = "STORE";
    private static final String STAGE_DONE = "DONE";

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_SKIPPED = "SKIPPED";

    private static final List<String> STAGE_ORDER = List.of(
            STAGE_SANITIZE, STAGE_DEDUP, STAGE_CHUNK, STAGE_ENHANCE, STAGE_EMBED, STAGE_STORE, STAGE_DONE);

    private final DocumentCleanService cleanService;
    private final SensitiveDataRedactor redactor;
    private final ChunkService chunkService;
    private final KbEnhanceService enhanceService;
    private final VectorIngestService vectorIngestService;
    private final KbIngestJobMapper jobMapper;
    private final LearningMaterialMapper materialMapper;
    private final RedisService redisService;
    private final KbProperties kbProperties;
    private final ObjectMapper objectMapper;

    public record IngestOutcome(KbIngestJob job, boolean skipped, boolean resumed) {
    }

    /** 扫描知识库根目录 */
    public List<IngestOutcome> scanAndIngest() {
        Path basePath = Path.of(kbProperties.getBasePath());
        if (!Files.exists(basePath)) {
            log.warn("KB base path does not exist: {}", basePath.toAbsolutePath());
            return List.of();
        }
        try (var stream = Files.walk(basePath)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(this::supported)
                    .toList();
            List<IngestOutcome> outcomes = new ArrayList<>();
            for (Path file : files) {
                outcomes.add(ingestFile(file));
            }
            return outcomes;
        } catch (Exception e) {
            throw new IllegalStateException("扫描知识库失败: " + e.getMessage(), e);
        }
    }

    /** 入库单个文件；同文件重复调用会复用未完成任务或跳过已完成任务 */
    public IngestOutcome ingestFile(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            String fileHash = sha256(bytes);
            KbIngestJob job = findOrCreateJob(file, fileHash);
            if (STATUS_COMPLETED.equals(job.getStatus())) {
                return new IngestOutcome(job, true, false);
            }

            String lockKey = "kb:ingest:" + fileHash;
            if (!redisService.tryLock(lockKey, Duration.ofMinutes(10))) {
                log.info("KB ingest already running for {}", file);
                return new IngestOutcome(job, true, true);
            }
            boolean resumed = job.getStage() != null && !STAGE_SANITIZE.equals(job.getStage());
            try {
                runStages(job);
            } finally {
                redisService.unlock(lockKey);
            }
            return new IngestOutcome(job, false, resumed);
        } catch (Exception e) {
            log.error("KB ingest failed for {}: {}", file, e.getMessage(), e);
            throw new IllegalStateException("入库失败: " + file.getFileName() + " - " + e.getMessage(), e);
        }
    }

    /** 手动从断点续跑 */
    public KbIngestJob resume(Long jobId) {
        KbIngestJob job = jobMapper.selectById(jobId);
        if (job == null) {
            throw new IllegalArgumentException("入库任务不存在: " + jobId);
        }
        if (STATUS_COMPLETED.equals(job.getStatus())) {
            return job;
        }
        job.setStatus(STATUS_RUNNING);
        jobMapper.updateById(job);
        runStages(job);
        return job;
    }

    public List<KbIngestJob> listJobs() {
        return jobMapper.selectList(new LambdaQueryWrapper<KbIngestJob>()
                .orderByDesc(KbIngestJob::getId));
    }

    private KbIngestJob findOrCreateJob(Path file, String fileHash) {
        KbIngestJob existing = jobMapper.selectOne(new LambdaQueryWrapper<KbIngestJob>()
                .eq(KbIngestJob::getFileHash, fileHash)
                .orderByDesc(KbIngestJob::getId)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        KbIngestJob job = new KbIngestJob();
        job.setSourcePath(file.toAbsolutePath().toString());
        job.setTitle(stripExtension(file.getFileName().toString()));
        job.setCategory("DOCUMENT");
        job.setFileHash(fileHash);
        job.setStage(STAGE_SANITIZE);
        job.setStatus(STATUS_RUNNING);
        job.setRetryCount(0);
        jobMapper.insert(job);
        return job;
    }

    private void runStages(KbIngestJob job) {
        int attempts = 0;
        int maxAttempts = Math.max(1, kbProperties.getMaxRetries() + 1);
        while (true) {
            try {
                executeFromCurrentStage(job);
                return;
            } catch (Exception e) {
                attempts++;
                if (attempts >= maxAttempts) {
                    job.setStatus(STATUS_FAILED);
                    job.setErrorMessage(truncate(e.getMessage(), 1000));
                    job.setRetryCount(attempts);
                    jobMapper.updateById(job);
                    throw new IllegalStateException("入库在阶段 " + job.getStage() + " 失败: " + e.getMessage(), e);
                }
                log.warn("KB ingest stage {} failed, retry {}/{}: {}",
                        job.getStage(), attempts, maxAttempts - 1, e.getMessage());
            }
        }
    }

    private void executeFromCurrentStage(KbIngestJob job) throws Exception {
        if (job.getStatus() == null || STATUS_FAILED.equals(job.getStatus())) {
            job.setStatus(STATUS_RUNNING);
            job.setErrorMessage(null);
            jobMapper.updateById(job);
        }
        if (pending(job, STAGE_SANITIZE)) {
            sanitize(job);
        }
        if (pending(job, STAGE_DEDUP) && !STATUS_SKIPPED.equals(job.getStatus())) {
            dedup(job);
        }
        if (STATUS_SKIPPED.equals(job.getStatus())) {
            return;
        }
        if (pending(job, STAGE_CHUNK)) {
            chunk(job);
        }
        if (pending(job, STAGE_ENHANCE)) {
            enhance(job);
        }
        if (pending(job, STAGE_EMBED)) {
            embed(job);
        }
        if (pending(job, STAGE_STORE)) {
            store(job);
        }
    }

    /** job.stage 表示"下一个待执行阶段"，故当前游标 <= 目标阶段时需要执行 */
    private boolean pending(KbIngestJob job, String stage) {
        int current = STAGE_ORDER.indexOf(job.getStage());
        return current <= STAGE_ORDER.indexOf(stage);
    }

    private void sanitize(KbIngestJob job) throws Exception {
        String text = job.getCleanText();
        if (text == null || text.isBlank()) {
            try (InputStream input = Files.newInputStream(Path.of(job.getSourcePath()))) {
                text = cleanService.extractText(input);
            }
        }
        job.setCleanText(redactor.redact(text));
        job.setStage(STAGE_DEDUP);
        jobMapper.updateById(job);
        log.info("KB job {} sanitized: {} chars", job.getId(), job.getCleanText().length());
    }

    private void dedup(KbIngestJob job) {
        String contentHash = sha256(job.getCleanText().getBytes(StandardCharsets.UTF_8));
        job.setContentHash(contentHash);
        Long duplicates = jobMapper.selectCount(new LambdaQueryWrapper<KbIngestJob>()
                .eq(KbIngestJob::getContentHash, contentHash)
                .eq(KbIngestJob::getStatus, STATUS_COMPLETED)
                .ne(KbIngestJob::getId, job.getId()));
        if (duplicates != null && duplicates > 0) {
            job.setStage(STAGE_DONE);
            job.setStatus(STATUS_SKIPPED);
            jobMapper.updateById(job);
            log.info("KB job {} skipped as duplicate (contentHash={})", job.getId(), contentHash);
            return;
        }
        job.setStage(STAGE_CHUNK);
        jobMapper.updateById(job);
    }

    private void chunk(KbIngestJob job) throws Exception {
        List<ChunkService.ChildChunk> chunks = chunkService.chunkParentChild(job.getCleanText());
        if (chunks.isEmpty()) {
            throw new IllegalStateException("文档清洗后没有可用内容");
        }
        job.setChunksJson(objectMapper.writeValueAsString(chunks));
        job.setStage(STAGE_ENHANCE);
        jobMapper.updateById(job);
        log.info("KB job {} chunked: {} children", job.getId(), chunks.size());
    }

    private void enhance(KbIngestJob job) throws Exception {
        List<ChunkService.ChildChunk> chunks = readChunks(job);
        List<String> parents = parentTexts(chunks);
        List<ParentMeta> metas = enhanceService.enhance(parents, job.getTitle());
        job.setEnhancedJson(objectMapper.writeValueAsString(metas));
        job.setStage(STAGE_EMBED);
        jobMapper.updateById(job);
        log.info("KB job {} enhanced: {} parent metas", job.getId(), metas.size());
    }

    private void embed(KbIngestJob job) throws Exception {
        List<ChunkService.ChildChunk> chunks = readChunks(job);
        Long materialId = job.getMaterialId();
        if (materialId == null) {
            materialId = createMaterial(job, readMetas(job), chunks);
            job.setMaterialId(materialId);
        }
        vectorIngestService.embed(materialId, chunks, buildMetaJson(job));
        job.setStage(STAGE_STORE);
        jobMapper.updateById(job);
    }

    private void store(KbIngestJob job) throws Exception {
        List<ChunkService.ChildChunk> chunks = readChunks(job);
        List<ParentMeta> metas = readMetas(job);
        vectorIngestService.storeToEs(job.getMaterialId(), job.getCategory(), chunks, metas);
        job.setStage(STAGE_DONE);
        job.setStatus(STATUS_COMPLETED);
        job.setErrorMessage(null);
        jobMapper.updateById(job);
        log.info("KB job {} completed: materialId={}", job.getId(), job.getMaterialId());
    }

    private Long createMaterial(KbIngestJob job, List<ParentMeta> metas, List<ChunkService.ChildChunk> chunks) {
        ParentMeta primary = metas.isEmpty()
                ? ParentMeta.fallback(job.getTitle(), chunks.get(0).parentContent())
                : metas.get(0);
        int difficulty = (int) Math.round(metas.stream().mapToInt(ParentMeta::difficulty).average().orElse(3));
        String summary = metas.stream()
                .limit(2)
                .map(ParentMeta::summary)
                .reduce((a, b) -> a + " " + b)
                .orElse(primary.summary());

        LearningMaterial material = new LearningMaterial();
        material.setTitle(primary.title());
        material.setSummary(truncate(summary, 500));
        material.setCategory(job.getCategory());
        material.setSource(job.getSourcePath());
        material.setDifficulty(Math.max(1, Math.min(5, difficulty)));
        material.setRecommendCount(0);
        materialMapper.insert(material);
        return material.getId();
    }

    private String buildMetaJson(KbIngestJob job) {
        String source = job.getSourcePath() == null ? "" : job.getSourcePath().replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"source\":\"" + source + "\",\"jobId\":" + job.getId()
                + ",\"contentHash\":\"" + job.getContentHash() + "\"}";
    }

    private List<ChunkService.ChildChunk> readChunks(KbIngestJob job) throws Exception {
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, ChunkService.ChildChunk.class);
        return objectMapper.readValue(job.getChunksJson(), type);
    }

    private List<ParentMeta> readMetas(KbIngestJob job) throws Exception {
        JavaType type = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, ParentMeta.class);
        return objectMapper.readValue(job.getEnhancedJson(), type);
    }

    private List<String> parentTexts(List<ChunkService.ChildChunk> chunks) {
        Map<Integer, String> parents = new TreeMap<>();
        for (ChunkService.ChildChunk chunk : chunks) {
            parents.putIfAbsent(chunk.parentIndex(), chunk.parentContent());
        }
        return new ArrayList<>(parents.values());
    }

    private boolean supported(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return Arrays.stream(kbProperties.getExtensions()).anyMatch(name::endsWith);
    }

    private String stripExtension(String fileName) {
        return fileName.replaceFirst("\\.[^.]+$", "");
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
