package com.studymate.ai.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.common.exception.BusinessException;
import com.studymate.common.trace.TraceContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LangChain4j MCP 客户端：按需连接外部 MCP Server，列出/调用其工具。
 * 所有调用带 traceId 与 Micrometer 指标；单个 Server 不可用不影响主链路。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalMcpClientService {

    private final McpClientProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();

    public List<String> serverNames() {
        return properties.getServers().stream().map(McpClientProperties.Server::getName).toList();
    }

    public List<Map<String, Object>> listTools(String serverName) {
        McpClient client = client(serverName);
        return TraceContext.wrap(() -> {
            TraceContext.enterSpan("mcp-client-list-tools");
            try {
                return client.listTools().stream().map(this::describe).toList();
            } finally {
                TraceContext.exitSpan();
            }
        }).get();
    }

    public Map<String, Object> callTool(String serverName, String toolName, Map<String, Object> arguments) {
        McpClient client = client(serverName);
        long started = System.currentTimeMillis();
        Timer.Sample sample = Timer.start(meterRegistry);
        TraceContext.enterSpan("mcp-client:" + toolName);
        try {
            String args = objectMapper.writeValueAsString(arguments == null ? Map.of() : arguments);
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(toolName)
                    .arguments(args)
                    .build();
            ToolExecutionResult result = client.executeTool(request);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("server", serverName);
            payload.put("tool", toolName);
            payload.put("isError", result.isError());
            payload.put("result", result.resultText());
            meterRegistry.counter("studymate.mcp.client.calls",
                    "server", serverName, "tool", toolName, "outcome", result.isError() ? "error" : "ok").increment();
            return payload;
        } catch (Exception e) {
            meterRegistry.counter("studymate.mcp.client.calls",
                    "server", serverName, "tool", toolName, "outcome", "exception").increment();
            throw new BusinessException(502, "调用 MCP 工具失败: " + e.getMessage());
        } finally {
            sample.stop(meterRegistry.timer("studymate.mcp.client.latency",
                    "server", serverName, "tool", toolName));
            log.debug("MCP tool {}@{} took {}ms", toolName, serverName, System.currentTimeMillis() - started);
            TraceContext.exitSpan();
        }
    }

    /** 已连接 Server 的最近一次错误，便于运维排查 */
    public Map<String, String> errors() {
        return Map.copyOf(errors);
    }

    private Map<String, Object> describe(ToolSpecification spec) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", spec.name());
        item.put("description", spec.description());
        item.put("parameters", spec.parameters() != null ? spec.parameters().toString() : "{}");
        return item;
    }

    private McpClient client(String serverName) {
        if (!properties.isEnabled()) {
            throw new BusinessException(503, "MCP 客户端未启用");
        }
        McpClientProperties.Server server = find(serverName)
                .orElseThrow(() -> new BusinessException(404, "未配置 MCP Server: " + serverName));
        McpClient existing = clients.get(serverName);
        if (existing != null) {
            return existing;
        }
        return clients.computeIfAbsent(serverName, key -> {
            try {
                McpClient created = build(server);
                created.listTools();
                errors.remove(serverName);
                log.info("MCP client connected: {} ({})", serverName, server.getUrl());
                return created;
            } catch (RuntimeException e) {
                errors.put(serverName, e.getMessage());
                log.warn("MCP client connect failed: {} → {}", serverName, e.getMessage());
                throw new BusinessException(502, "连接 MCP Server 失败: " + serverName);
            }
        });
    }

    private McpClient build(McpClientProperties.Server server) {
        Duration timeout = Duration.ofSeconds(server.getTimeoutSeconds());
        McpTransport transport = "sse".equalsIgnoreCase(server.getType())
                ? new HttpMcpTransport.Builder()
                    .sseUrl(server.getUrl())
                    .customHeaders(withTrace(server.getHeaders()))
                    .timeout(timeout)
                    .build()
                : new StreamableHttpMcpTransport.Builder()
                    .url(server.getUrl())
                    .customHeaders(withTrace(server.getHeaders()))
                    .timeout(timeout)
                    .build();
        return DefaultMcpClient.builder()
                .key(server.getName())
                .transport(transport)
                .clientName("studymate-mcp-client")
                .clientVersion("1.0.0")
                .toolExecutionTimeout(timeout)
                .build();
    }

    private java.util.function.Supplier<Map<String, String>> withTrace(Map<String, String> headers) {
        return () -> {
            Map<String, String> merged = new HashMap<>(headers);
            merged.put("X-Trace-Id", TraceContext.current());
            return merged;
        };
    }

    private Optional<McpClientProperties.Server> find(String name) {
        return properties.getServers().stream()
                .filter(s -> s.getName().equals(name))
                .findFirst();
    }

    @PreDestroy
    void close() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Close MCP client failed: {}", e.getMessage());
            }
        });
        clients.clear();
    }
}
