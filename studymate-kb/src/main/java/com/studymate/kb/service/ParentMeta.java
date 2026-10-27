package com.studymate.kb.service;

import java.util.List;

/** 父块的 LLM 增强元数据 */
public record ParentMeta(String title, String summary, List<String> keywords, int difficulty) {
    public static ParentMeta fallback(String title, String parentText) {
        String summary = parentText.length() > 80 ? parentText.substring(0, 80) : parentText;
        return new ParentMeta(title, summary, List.of(), 3);
    }
}
