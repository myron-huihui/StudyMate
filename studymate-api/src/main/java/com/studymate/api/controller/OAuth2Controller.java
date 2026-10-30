package com.studymate.api.controller;

import com.studymate.api.dto.AuthDtos;
import com.studymate.api.service.AuthService;
import com.studymate.common.ApiResponse;
import com.studymate.common.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * OAuth2 授权码 + PKCE：
 * 1) POST /oauth2/authorize 校验账号并返回带 code 的回调地址
 * 2) POST /oauth2/token 用 code + code_verifier 换令牌，或 refresh_token 轮换
 */
@Tag(name = "OAuth2", description = "授权码 + PKCE 流程")
@RestController
@RequiredArgsConstructor
public class OAuth2Controller {

    private final AuthService authService;

    @Operation(summary = "授权端点：校验用户并下发一次性授权码")
    @PostMapping("/oauth2/authorize")
    public ApiResponse<Map<String, Object>> authorize(@Valid @RequestBody AuthDtos.AuthorizeRequest req) {
        String redirectUrl = authService.authorize(req);
        return ApiResponse.ok(Map.of("redirectUrl", redirectUrl));
    }

    @Operation(summary = "令牌端点：authorization_code 或 refresh_token")
    @PostMapping(value = "/oauth2/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public Map<String, Object> token(@RequestParam("grant_type") String grantType,
                                     @RequestParam(value = "code", required = false) String code,
                                     @RequestParam(value = "client_id", required = false) String clientId,
                                     @RequestParam(value = "redirect_uri", required = false) String redirectUri,
                                     @RequestParam(value = "code_verifier", required = false) String codeVerifier,
                                     @RequestParam(value = "refresh_token", required = false) String refreshToken) {
        AuthDtos.TokenResponse response;
        if ("authorization_code".equals(grantType)) {
            if (code == null) {
                throw new BusinessException(400, "invalid_request: 缺少 code");
            }
            response = authService.exchangeCode(code, clientId, redirectUri, codeVerifier);
        } else if ("refresh_token".equals(grantType)) {
            if (refreshToken == null) {
                throw new BusinessException(400, "invalid_request: 缺少 refresh_token");
            }
            response = authService.refresh(refreshToken);
        } else {
            throw new BusinessException(400, "unsupported_grant_type");
        }
        return Map.of(
                "access_token", response.getAccessToken(),
                "refresh_token", response.getRefreshToken(),
                "token_type", response.getTokenType(),
                "expires_in", response.getExpiresIn());
    }

    @Operation(summary = "授权服务器元数据")
    @GetMapping("/.well-known/oauth-authorization-server")
    public Map<String, Object> metadata() {
        return Map.of(
                "issuer", "studymate",
                "authorization_endpoint", "/oauth2/authorize",
                "token_endpoint", "/oauth2/token",
                "grant_types_supported", List.of("authorization_code", "refresh_token"),
                "code_challenge_methods_supported", List.of("S256", "plain"));
    }
}
