package com.studymate.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studymate.api.dto.AuthDtos;
import com.studymate.api.security.JwtService;
import com.studymate.api.security.SecurityProperties;
import com.studymate.common.entity.RefreshToken;
import com.studymate.common.entity.User;
import com.studymate.common.exception.BusinessException;
import com.studymate.dal.mapper.RefreshTokenMapper;
import com.studymate.dal.mapper.UserMapper;
import com.studymate.dal.redis.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 认证服务：BCrypt 口令、授权码 + PKCE、刷新令牌轮换与复用检测。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserMapper userMapper;
    private final RefreshTokenMapper refreshTokenMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;
    private final RedisService redisService;
    private final ObjectMapper objectMapper;

    /** 授权码载荷（存 Redis，5 分钟一次性） */
    public record AuthCodeData(Long userId, String clientId, String redirectUri,
                               String codeChallenge, String codeChallengeMethod) {
    }

    public User register(AuthDtos.RegisterRequest req) {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, req.getUsername()));
        if (count != null && count > 0) {
            throw new BusinessException(409, "用户名已被占用");
        }
        User user = new User();
        user.setUsername(req.getUsername());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setEmail(req.getEmail());
        user.setRole("USER");
        userMapper.insert(user);
        return user;
    }

    public AuthDtos.TokenResponse login(String username, String password) {
        User user = authenticate(username, password);
        return issueTokens(user, UUID.randomUUID().toString());
    }

    /** 授权码：校验账号口令与 PKCE 参数，返回带 code 的回调地址 */
    public String authorize(AuthDtos.AuthorizeRequest req) {
        User user = authenticate(req.getUsername(), req.getPassword());
        String method = req.getCodeChallengeMethod() == null ? "S256" : req.getCodeChallengeMethod();
        if (!"S256".equalsIgnoreCase(method) && !"plain".equalsIgnoreCase(method)) {
            throw new BusinessException(400, "不支持的 code_challenge_method");
        }
        String code = randomToken(32);
        AuthCodeData data = new AuthCodeData(user.getId(), req.getClientId(), req.getRedirectUri(),
                req.getCodeChallenge(), method);
        try {
            redisService.saveAuthCode(code, objectMapper.writeValueAsString(data),
                    Duration.ofSeconds(securityProperties.getAuthCodeSeconds()));
        } catch (Exception e) {
            throw new BusinessException(500, "授权码保存失败");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(req.getRedirectUri())
                .queryParam("code", code);
        if (req.getState() != null && !req.getState().isBlank()) {
            builder.queryParam("state", req.getState());
        }
        return builder.build().toUriString();
    }

    /** 授权码换令牌：code 一次性消费 + PKCE 校验 */
    public AuthDtos.TokenResponse exchangeCode(String code, String clientId,
                                               String redirectUri, String codeVerifier) {
        String json = redisService.takeAuthCode(code);
        if (json == null) {
            throw new BusinessException(400, "invalid_grant: 授权码无效或已使用");
        }
        AuthCodeData data;
        try {
            data = objectMapper.readValue(json, AuthCodeData.class);
        } catch (Exception e) {
            throw new BusinessException(400, "invalid_grant: 授权码损坏");
        }
        if (clientId != null && !clientId.equals(data.clientId())) {
            throw new BusinessException(400, "invalid_grant: client_id 不匹配");
        }
        if (redirectUri != null && !redirectUri.equals(data.redirectUri())) {
            throw new BusinessException(400, "invalid_grant: redirect_uri 不匹配");
        }
        verifyPkce(codeVerifier, data.codeChallenge(), data.codeChallengeMethod());

        User user = userMapper.selectById(data.userId());
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        return issueTokens(user, UUID.randomUUID().toString());
    }

    /** 刷新令牌轮换：旧令牌立即作废，复用旧令牌则吊销整个 family */
    public AuthDtos.TokenResponse refresh(String rawToken) {
        RefreshToken current = findByHash(hash(rawToken));
        if (current == null) {
            throw new BusinessException(401, "invalid_grant: 刷新令牌无效");
        }
        if (Boolean.TRUE.equals(current.getRevoked())) {
            revokeFamily(current.getFamilyId());
            throw new BusinessException(401, "invalid_grant: 刷新令牌已复用，已吊销该会话全部令牌");
        }
        if (current.getExpiresAt() == null || current.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException(401, "invalid_grant: 刷新令牌已过期");
        }

        String newRaw = randomToken(48);
        String newHash = hash(newRaw);
        int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, current.getId())
                .eq(RefreshToken::getRevoked, false)
                .set(RefreshToken::getRevoked, true)
                .set(RefreshToken::getReplacedBy, newHash));
        if (updated == 0) {
            revokeFamily(current.getFamilyId());
            throw new BusinessException(401, "invalid_grant: 刷新令牌并发复用，已吊销该会话全部令牌");
        }

        User user = userMapper.selectById(current.getUserId());
        if (user == null) {
            throw new BusinessException(401, "用户不存在");
        }
        insertRefreshToken(user.getId(), newHash, current.getFamilyId());
        return buildResponse(user, newRaw);
    }

    public void logout(String rawToken) {
        RefreshToken token = findByHash(hash(rawToken));
        if (token == null) {
            return;
        }
        revokeFamily(token.getFamilyId());
    }

    private User authenticate(String username, String password) {
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username)
                .last("LIMIT 1"));
        if (user == null || user.getPassword() == null
                || !passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException(401, "用户名或密码错误");
        }
        return user;
    }

    private AuthDtos.TokenResponse issueTokens(User user, String familyId) {
        String refreshRaw = randomToken(48);
        insertRefreshToken(user.getId(), hash(refreshRaw), familyId);
        return buildResponse(user, refreshRaw);
    }

    private AuthDtos.TokenResponse buildResponse(User user, String refreshRaw) {
        JwtService.IssuedToken access = jwtService.issue(user);
        AuthDtos.TokenResponse response = new AuthDtos.TokenResponse();
        response.setAccessToken(access.value());
        response.setRefreshToken(refreshRaw);
        response.setExpiresIn(access.expiresInSeconds());
        return response;
    }

    private void insertRefreshToken(Long userId, String tokenHash, String familyId) {
        RefreshToken token = new RefreshToken();
        token.setUserId(userId);
        token.setTokenHash(tokenHash);
        token.setFamilyId(familyId);
        token.setExpiresAt(LocalDateTime.now().plus(securityProperties.getRefreshTokenDays(), ChronoUnit.DAYS));
        token.setRevoked(false);
        token.setCreatedAt(LocalDateTime.now());
        refreshTokenMapper.insert(token);
    }

    private RefreshToken findByHash(String tokenHash) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, tokenHash)
                .last("LIMIT 1"));
    }

    private void revokeFamily(String familyId) {
        int revoked = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getFamilyId, familyId)
                .eq(RefreshToken::getRevoked, false)
                .set(RefreshToken::getRevoked, true));
        log.warn("Revoked {} refresh tokens in family {}", revoked, familyId);
    }

    private void verifyPkce(String verifier, String challenge, String method) {
        if (verifier == null || verifier.isBlank()) {
            throw new BusinessException(400, "invalid_grant: 缺少 code_verifier");
        }
        if (challenge == null || challenge.isBlank()) {
            throw new BusinessException(400, "invalid_grant: 缺少 code_challenge");
        }
        String computed;
        try {
            if ("S256".equalsIgnoreCase(method)) {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.UTF_8));
                computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } else {
                computed = verifier;
            }
        } catch (Exception e) {
            throw new BusinessException(400, "invalid_grant: PKCE 校验失败");
        }
        boolean matched = MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                challenge.getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            throw new BusinessException(400, "invalid_grant: code_verifier 与 challenge 不匹配");
        }
    }

    private String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
