package com.studymate.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 全链路追踪入口：沿用上游 traceId，缺失时新建；同时把真实客户端 IP 透传给下游。
 */
@Slf4j
@Component
public class GatewayFilter implements GlobalFilter, Ordered {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String CLIENT_IP_HEADER = "X-Client-Ip";

    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        String clientIp = extractClientIp(request);

        ServerHttpRequest mutated = request.mutate()
                .header(TRACE_HEADER, traceId)
                .header(CLIENT_IP_HEADER, clientIp)
                .build();
        exchange.getResponse().getHeaders().set(TRACE_HEADER, traceId);
        log.debug("Gateway trace {} {} -> {}", traceId, request.getMethod(), request.getPath());
        return chain.filter(exchange.mutate().request(mutated).build());
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
}
