package com.studymate.ai.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 思维链剥离：只计量不入上下文 */
@Slf4j
public final class ThinkingExtractor {

    private static final Pattern TAGGED = Pattern.compile(
            "<(?:thinking|reasoning)>(.*?)</(?:thinking|reasoning)>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FENCED = Pattern.compile(
            "```(?:thinking|reasoning)\\s*(.*?)```", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private ThinkingExtractor() {
    }

    public record Split(String content, String thinking) {
    }

    public static Split split(String raw) {
        if (raw == null || raw.isBlank()) {
            return new Split(raw, "");
        }
        StringBuilder thinking = new StringBuilder();
        String content = TAGGED.matcher(raw).replaceAll(m -> {
            thinking.append(m.group(1)).append('\n');
            return "";
        });
        content = FENCED.matcher(content).replaceAll(m -> {
            thinking.append(m.group(1)).append('\n');
            return "";
        });
        return new Split(content.trim(), thinking.toString().trim());
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x2E80) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return (int) Math.ceil(cjk / 1.5 + other / 4.0);
    }

    static long backoffMillis(int attempt, long baseMs, long maxMs) {
        long exp = Math.min(maxMs, baseMs * (1L << Math.min(attempt - 1, 6)));
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, exp / 4));
        return exp + jitter;
    }
}
