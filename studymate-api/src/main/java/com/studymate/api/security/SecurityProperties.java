package com.studymate.api.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** JWT 与 OAuth2 安全参数 */
@Data
@Component
@ConfigurationProperties(prefix = "security.jwt")
public class SecurityProperties {
    /** HMAC-SHA256 密钥，长度至少 32 字节 */
    private String secret = "studymate-dev-only-secret-key-please-change";
    private String issuer = "studymate";
    private long accessTokenMinutes = 30;
    private long refreshTokenDays = 7;
    /** 授权码有效期（秒） */
    private long authCodeSeconds = 300;
}
