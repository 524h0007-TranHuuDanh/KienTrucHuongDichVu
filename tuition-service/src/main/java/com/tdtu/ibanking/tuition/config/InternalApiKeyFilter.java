package com.tdtu.ibanking.tuition.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;

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

@Component
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Api-Key";

    private static final List<String> PROTECTED_PATTERNS = List.of(
            "/api/tuition/*/mark-paid");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        boolean isProtected = PROTECTED_PATTERNS.stream().anyMatch(p -> pathMatcher.match(p, path));

        if (isProtected) {
            String providedKey = request.getHeader(HEADER_NAME);

            if (providedKey == null || !constantTimeEquals(providedKey, internalApiKey)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"message\":\"Endpoint nội bộ, không được gọi trực tiếp\"}");
                return;
            } else {
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken("internal-service", null, Collections.emptyList()));
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null)
            return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}