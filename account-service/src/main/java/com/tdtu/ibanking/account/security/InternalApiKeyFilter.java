package com.tdtu.ibanking.account.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * account-service không có Spring Security/JWT (không login trực tiếp end-user) -
 * toàn bộ endpoint dưới /api/account/** bắt buộc header X-Internal-Api-Key, khác
 * với auth-service (nơi filter gốc này còn phải phối hợp với JWT cho GET /users/*).
 * Đăng ký urlPatterns=/api/account/* trong config/WebConfig để không chặn
 * /actuator/health (dùng cho healthcheck docker-compose, không cần xác thực) và
 * /swagger-ui, /v3/api-docs.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Api-Key";

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {

        String providedKey = request.getHeader(HEADER_NAME);
        if (providedKey == null || !constantTimeEquals(providedKey, internalApiKey)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"Endpoint nội bộ, không được gọi trực tiếp\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
