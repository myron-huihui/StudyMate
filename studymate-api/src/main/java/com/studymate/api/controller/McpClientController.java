package com.studymate.api.controller;

import com.studymate.ai.mcp.ExternalMcpClientService;
import com.studymate.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 反向接入：以 MCP 客户端身份消费外部 MCP Server 的工具。
 */
@Tag(name = "外部 MCP 工具", description = "查看/调用外部 MCP Server 提供的工具")
@RestController
@RequestMapping("/api/v1/mcp/servers")
@RequiredArgsConstructor
public class McpClientController {

    private final ExternalMcpClientService mcpClientService;

    @Operation(summary = "已配置的外部 MCP Server 列表")
    @GetMapping
    public ResponseEntity<ApiResponse<List<String>>> servers() {
        return ResponseEntity.ok(ApiResponse.ok(mcpClientService.serverNames()));
    }

    @Operation(summary = "列出某个 MCP Server 的工具")
    @GetMapping("/{server}/tools")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> tools(@PathVariable String server) {
        return ResponseEntity.ok(ApiResponse.ok(mcpClientService.listTools(server)));
    }

    @Operation(summary = "调用某个 MCP Server 的工具")
    @PostMapping("/{server}/tools/{tool}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> call(
            @PathVariable String server,
            @PathVariable String tool,
            @RequestBody(required = false) Map<String, Object> arguments) {
        return ResponseEntity.ok(ApiResponse.ok(mcpClientService.callTool(server, tool, arguments)));
    }
}
