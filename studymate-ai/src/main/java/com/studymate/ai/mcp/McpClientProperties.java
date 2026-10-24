package com.studymate.ai.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部 MCP Server 接入配置（反向接入：StudyMate 作为 MCP 客户端消费第三方工具）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai.mcp")
public class McpClientProperties {

    private boolean enabled = true;
    private List<Server> servers = new ArrayList<>();

    @Data
    public static class Server {
        private String name;
        /** streamable-http 填 POST 端点；sse 填 SSE 端点 */
        private String url;
        private String type = "streamable-http";
        private Map<String, String> headers = new HashMap<>();
        private int timeoutSeconds = 30;
    }
}
