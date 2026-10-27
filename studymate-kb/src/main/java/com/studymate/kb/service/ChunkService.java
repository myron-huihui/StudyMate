package com.studymate.kb.service;

import com.studymate.ai.config.AiProperties;
import com.studymate.kb.config.KbProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块：大块（parent）保留完整语义供精排/引用，小块（child）用于向量召回。
 * child 命中后由 parentContent 补足上下文，避免片段化回答。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkService {

    private final AiProperties aiProperties;
    private final KbProperties kbProperties;

    public record ChildChunk(int index, int parentIndex, String content, String parentContent) {
    }

    public List<ChildChunk> chunkParentChild(String text) {
        int parentSize = kbProperties.getParentChunkSize();
        int childSize = kbProperties.getChildChunkSize() > 0
                ? kbProperties.getChildChunkSize() : aiProperties.getChunkSize();
        int overlap = kbProperties.getChildOverlap() > 0
                ? kbProperties.getChildOverlap() : aiProperties.getChunkOverlap();

        List<String> parents = split(text, parentSize, 0);
        List<ChildChunk> result = new ArrayList<>();
        int childIndex = 0;
        for (int p = 0; p < parents.size(); p++) {
            String parent = parents.get(p);
            for (String child : split(parent, childSize, overlap)) {
                result.add(new ChildChunk(childIndex++, p, child, parent));
            }
        }
        log.debug("Parent-child chunked: {} parents, {} children", parents.size(), result.size());
        return result;
    }

    /** 按段落贪心聚合到 size，超长段落按句子/硬切，overlap 为尾部重叠字符数 */
    public List<String> split(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        for (String para : text.split("\n\n")) {
            String p = para.trim();
            if (!p.isEmpty()) {
                appendParagraph(chunks, p, size, overlap);
            }
        }
        return chunks;
    }

    private void appendParagraph(List<String> chunks, String para, int size, int overlap) {
        if (para.length() <= size) {
            chunks.add(para);
            return;
        }
        int start = 0;
        while (start < para.length()) {
            int end = Math.min(start + size, para.length());
            if (end < para.length()) {
                int boundary = lastBoundary(para, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            chunks.add(para.substring(start, end).trim());
            if (end >= para.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }
    }

    private int lastBoundary(String text, int start, int end) {
        for (int i = end - 1; i > start; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '\n' || c == '.' || c == '!' || c == '?') {
                return i + 1;
            }
        }
        return end;
    }
}
