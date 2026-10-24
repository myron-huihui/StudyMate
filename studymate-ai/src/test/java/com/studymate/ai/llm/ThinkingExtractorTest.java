package com.studymate.ai.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingExtractorTest {

    @Test
    void shouldStripThinkingFromContext() {
        String raw = "<thinking>先分析目标，再拆解</thinking>这是给用户的计划正文。";
        ThinkingExtractor.Split split = ThinkingExtractor.split(raw);

        assertEquals("这是给用户的计划正文。", split.content());
        assertEquals("先分析目标，再拆解", split.thinking());
        assertTrue(split.content().indexOf("thinking") < 0);
    }

    @Test
    void shouldStripFencedThinking() {
        String raw = "```thinking\n内部推理\n```\n最终答案";
        ThinkingExtractor.Split split = ThinkingExtractor.split(raw);

        assertFalse(split.content().contains("内部推理"));
        assertTrue(split.content().contains("最终答案"));
    }

    @Test
    void shouldKeepPlainContent() {
        ThinkingExtractor.Split split = ThinkingExtractor.split("普通回答");
        assertEquals("普通回答", split.content());
        assertEquals("", split.thinking());
    }
}
