package com.studymate.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 模型配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {
    private String model = "deepseek-v4.1-flash";
    private String embeddingModel = "qwen3.7-text-embedding";
    private double temperature = 0.7;
    private int maxTokens = 4096;
    /** 向量维度 */
    private int embeddingDim = 1024;
    /** 知识库分块大小（字符数） */
    private int chunkSize = 512;
    /** 分块重叠 */
    private int chunkOverlap = 64;
    /** RAG 召回 topK */
    private int ragTopK = 5;
    private Rag rag = new Rag();
    private Bailian bailian = new Bailian();
    private Agent agent = new Agent();

    @Data
    public static class Rag {
        private boolean rewriteEnabled = true;
        private int recallTopK = 20;
        private int rerankTopK = 20;
        private int contextBudget = 2400;
        private long routeTimeoutMs = 1200;
        private String rerankEndpoint = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private String rerankModel = "gte-rerank-v2";
        private long rerankTimeoutMs = 1500;
        private int rerankFailureThreshold = 3;
        private long rerankOpenMs = 30_000;
    }

    @Data
    public static class Agent {
        /** 审查不通过时最多重写次数，超限标记 needsManual 但仍返回计划 */
        private int maxRewrites = 2;
        /** 工作流整体超时（秒） */
        private int workflowTimeoutSeconds = 120;
    }

    @Data
    public static class Bailian {
        private String apiKey = "";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode";
    }
}
