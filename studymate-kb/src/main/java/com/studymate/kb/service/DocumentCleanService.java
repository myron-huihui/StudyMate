package com.studymate.kb.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 文档清洗：Tika 抽取纯文本 + 噪声清理
 */
@Slf4j
@Service
public class DocumentCleanService {

    /** 从输入流提取纯文本（不限制长度） */
    public String extractText(InputStream input) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(-1);
        parser.parse(input, handler, new Metadata(), new ParseContext());
        return clean(handler.toString());
    }

    /** 清洗文本：去控制字符、压缩空白、合并空行 */
    public String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("[ \\t]{2,}", " ")
                .replaceAll("\\s{3,}", "\n\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
