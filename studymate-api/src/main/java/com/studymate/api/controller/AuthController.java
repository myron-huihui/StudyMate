package com.studymate.api.controller;

import com.studymate.api.dto.AuthDtos;
import com.studymate.api.service.AuthService;
import com.studymate.common.ApiResponse;
import com.studymate.common.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 账号认证：注册、登录、刷新令牌轮换、登出
 */
@Tag(name = "认证", description = "注册 / 登录 / 刷新令牌 / 登出")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "注册")
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        User user = authService.register(req);
        return ApiResponse.ok(Map.of("id", user.getId(), "username", user.getUsername()));
    }

    @Operation(summary = "登录并签发访问/刷新令牌")
    @PostMapping("/login")
    public ApiResponse<AuthDtos.TokenResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return ApiResponse.ok(authService.login(req.getUsername(), req.getPassword()));
    }

    @Operation(summary = "刷新令牌（轮换；旧令牌复用将吊销整个会话）")
    @PostMapping("/refresh")
    public ApiResponse<AuthDtos.TokenResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        return ApiResponse.ok(authService.refresh(req.getRefreshToken()));
    }

    @Operation(summary = "登出（吊销该会话全部刷新令牌）")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody AuthDtos.RefreshRequest req) {
        authService.logout(req.getRefreshToken());
        return ApiResponse.ok();
    }
}
