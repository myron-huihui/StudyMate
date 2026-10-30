package com.studymate.api.security;

import com.studymate.common.trace.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer Token 解析：校验通过后把 userId 写入 SecurityContext；同时贯通 traceId。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        TraceContext.set(traceId != null && !traceId.isBlank() ? traceId : TraceContext.newTraceId());
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                authenticate(header.substring(7));
            }
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    private void authenticate(String token) {
        try {
            Jwt jwt = jwtService.decode(token);
            Long userId = Long.valueOf(jwt.getSubject());
            String role = jwt.getClaimAsString("role");
            var authentication = new UsernamePasswordAuthenticationToken(
                    userId, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + (role == null ? "USER" : role))));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            log.debug("JWT rejected: {}", e.getMessage());
            SecurityContextHolder.clearContext();
        }
    }
}
