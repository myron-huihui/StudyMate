package com.studymate.kb.controller;

import com.studymate.common.ApiResponse;
import com.studymate.common.entity.KbIngestJob;
import com.studymate.kb.service.KbIngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

/**
 * 知识库离线流水线接口：扫描、单文件入库、任务列表、断点续跑
 */
@Tag(name = "知识库", description = "六阶段离线入库流水线")
@RestController
@RequestMapping("/api/v1/kb")
@RequiredArgsConstructor
public class KbController {

    private final KbIngestService ingestService;

    @Operation(summary = "扫描知识库根目录并入库")
    @PostMapping("/scan")
    public ApiResponse<List<KbIngestService.IngestOutcome>> scan() {
        return ApiResponse.ok(ingestService.scanAndIngest());
    }

    @Operation(summary = "入库单个文件")
    @PostMapping("/ingest")
    public ApiResponse<KbIngestService.IngestOutcome> ingest(@RequestParam String path) {
        return ApiResponse.ok(ingestService.ingestFile(Path.of(path)));
    }

    @Operation(summary = "入库任务列表")
    @GetMapping("/jobs")
    public ApiResponse<List<KbIngestJob>> jobs() {
        return ApiResponse.ok(ingestService.listJobs());
    }

    @Operation(summary = "从断点续跑")
    @PostMapping("/jobs/{id}/resume")
    public ApiResponse<KbIngestJob> resume(@PathVariable Long id) {
        return ApiResponse.ok(ingestService.resume(id));
    }
}
