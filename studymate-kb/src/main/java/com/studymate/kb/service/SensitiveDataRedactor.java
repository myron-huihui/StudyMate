package com.studymate.kb.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 正则脱敏：入库前抹掉手机号、邮箱、身份证、银行卡、密钥等敏感信息
 */
@Slf4j
@Service
public class SensitiveDataRedactor {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(sk-[A-Za-z0-9]{16,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{20,})\\b");

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String redacted = text;
        int hits = 0;
        for (Pattern pattern : new Pattern[]{PHONE, EMAIL, ID_CARD, BANK_CARD, SECRET}) {
            hits += countMatches(redacted, pattern);
            redacted = pattern.matcher(redacted).replaceAll("[REDACTED]");
        }
        if (hits > 0) {
            log.info("Redacted {} sensitive fragments before storage", hits);
        }
        return redacted;
    }

    private int countMatches(String text, Pattern pattern) {
        var matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
