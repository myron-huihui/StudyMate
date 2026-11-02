package com.studymate.gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Redis Lua 的固定窗口 IP 限流。
 * Redis 不可用时降级为本地窗口计数（放行优先，不阻断业务）。
 * 作为 WebFilter 运行在 Security 之前，避免未认证请求绕过限流。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IpRateLimitFilter implements WebFilter, Ordered {

    private static final DefaultRedisScript<Long> FIXED_WINDOW = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return current", Long.class);

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${gateway.rate-limit.requests:100}")
    private int maxRequests;

    @Value("${gateway.rate-limit.window-seconds:60}")
    private int windowSeconds;

    private final Map<String, LocalWindow> localWindows = new ConcurrentHashMap<>();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (isWhitelisted(path)) {
            return chain.filter(exchange);
        }
        String ip = extractClientIp(request);
        long window = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = "studymate:gw:rate:" + ip + ":" + window;

        return redisTemplate.execute(FIXED_WINDOW, List.of(key), String.valueOf(windowSeconds + 1))
                .next()
                .flatMap(count -> count > maxRequests
                        ? reject(exchange, ip, count)
                        : chain.filter(exchange))
                .onErrorResume(error -> {
                    log.warn("Redis rate limiter unavailable, fallback to local counter: {}", error.getMessage());
                    return localLimit(exchange, chain, ip);
                });
    }

    private Mono<Void> localLimit(ServerWebExchange exchange, WebFilterChain chain, String ip) {
        long now = System.currentTimeMillis();
        LocalWindow window = localWindows.computeIfAbsent(ip, k -> new LocalWindow(now));
        synchronized (window) {
            if (now - window.startedAt > windowSeconds * 1000L) {
                window.startedAt = now;
                window.count.set(0);
            }
            if (window.count.incrementAndGet() > maxRequests) {
                return reject(exchange, ip, window.count.get());
            }
        }
        return chain.filter(exchange);
    }

    private Mono<Void> reject(ServerWebExchange exchange, String ip, long count) {
        log.warn("Rate limited: ip={} count={} limit={}/{}s", ip, count, maxRequests, windowSeconds);
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            DataBuffer buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(
                    Map.of("code", 429, "message", "请求过于频繁，请稍后重试")));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    private boolean isWhitelisted(String path) {
        return path.startsWith("/actuator") || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs") || path.startsWith("/.well-known")
                || path.equals("/");
    }

    private String extractClientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        if (request.getRemoteAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return "unknown";
    }

    private static final class LocalWindow {
        private long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private LocalWindow(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
