package com.studymate.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 认证相关请求/响应 */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Data
    @Schema(description = "注册请求")
    public static class RegisterRequest {
        @NotBlank
        @Size(min = 3, max = 64)
        private String username;
        @NotBlank
        @Size(min = 8, max = 72, message = "密码长度需为 8-72 位")
        private String password;
        private String email;
    }

    @Data
    @Schema(description = "登录请求")
    public static class LoginRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
    }

    @Data
    @Schema(description = "授权码申请（含 PKCE）")
    public static class AuthorizeRequest {
        @NotBlank
        private String username;
        @NotBlank
        private String password;
        private String clientId = "studymate-client";
        private String redirectUri = "http://localhost:18080/callback";
        @NotBlank(message = "PKCE code_challenge 不能为空")
        private String codeChallenge;
        /** S256（默认）或 plain */
        private String codeChallengeMethod = "S256";
        private String state;
    }

    @Data
    @Schema(description = "刷新/登出请求")
    public static class RefreshRequest {
        @NotBlank
        private String refreshToken;
    }

    @Data
    @Schema(description = "令牌响应")
    public static class TokenResponse {
        private String accessToken;
        private String refreshToken;
        private String tokenType = "Bearer";
        private long expiresIn;
    }
}
