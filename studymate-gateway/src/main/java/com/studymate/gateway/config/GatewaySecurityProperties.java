package com.studymate.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 与 studymate-api 共享同一套 HMAC 密钥与 issuer */
@Data
@Component
@ConfigurationProperties(prefix = "security.jwt")
public class GatewaySecurityProperties {
    private String secret = "studymate-dev-only-secret-key-please-change";
    private String issuer = "studymate";
}
