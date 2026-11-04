package com.studymate.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * StudyMate MCP Server 主应用
 * 通过 Spring AI MCP Server 暴露工具能力。
 * 复用 API 模块的 service/config，但排除 API 的 Web 与安全装配（那是 API 进程的职责）。
 */
@SpringBootApplication
@ComponentScan(
        basePackages = "com.studymate",
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.controller\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.security\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.config\\.(SecurityConfig|SwaggerConfig|SentinelConfig)"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.StudyMateApiApplication"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.error\\..*"),
                @ComponentScan.Filter(type = FilterType.REGEX,
                        pattern = "com\\.studymate\\.api\\.service\\.AuthService")
        })
public class StudyMateMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(StudyMateMcpApplication.class, args);
    }
}
