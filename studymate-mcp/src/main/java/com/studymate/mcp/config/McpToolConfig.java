package com.studymate.mcp.config;

import com.studymate.mcp.tool.StudyMateMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 @Tool 注解方法注册进 MCP Server 的工具表。
 */
@Configuration
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider studyMateToolCallbackProvider(StudyMateMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
