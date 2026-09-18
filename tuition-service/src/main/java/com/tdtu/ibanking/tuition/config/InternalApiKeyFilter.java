package com.tdtu.ibanking.tuition.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Hai route GET tra học phí có hai loại người gọi: payment-service (mang internal key,
 * không có JWT của người dùng để chuyển tiếp) và frontend qua gateway (mang JWT).
 * Nên cả hai đều được chấp nhận.
 *
 * <p>mark-paid thì bắt buộc internal key: gọi thẳng được vào đây là bỏ qua toàn bộ
 * luồng OTP và trừ tiền bên payment-service.
 */
@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Api-Key";

    private static final Map<String, List<String>> INTERNAL_ONLY_ROUTES = Map.of(
            "POST", List.of("/api/tuition/*/mark-paid"));

    private static final Map<String, List<String>> INTERNAL_OR_JWT_ROUTES = Map.of(
            "GET", List.of("/api/tuition/*", "/api/tuition/id/*"));

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        if (matches(INTERNAL_ONLY_ROUTES, method, path)) {
            String providedKey = request.getHeader(HEADER_NAME);
            if (providedKey == null || !constantTimeEquals(providedKey, internalApiKey)) {
                reject(response);
                return;
            }
            authenticateAsInternalService();
        } else if (matches(INTERNAL_OR_JWT_ROUTES, method, path)) {
            String providedKey = request.getHeader(HEADER_NAME);
            if (providedKey != null) {
                if (!constantTimeEquals(providedKey, internalApiKey)) {
                    reject(response);
                    return;
                }
                authenticateAsInternalService();
            }
            // Không có key -> để JwtAuthenticationFilter xác thực bằng JWT người dùng
        }

        filterChain.doFilter(request, response);
    }

    private boolean matches(Map<String, List<String>> routes, String method, String path) {
        return routes.getOrDefault(method, List.of()).stream()
                .anyMatch(p -> pathMatcher.match(p, path));
    }

    private void authenticateAsInternalService() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("internal-service", null, Collections.emptyList()));
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Endpoint nội bộ, không được gọi trực tiếp\"}");
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
