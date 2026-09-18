package com.tdtu.ibanking.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
/**
 * Hai mức bảo vệ khác nhau: debit/credit chỉ service nội bộ được gọi, còn
 * GET /users/* thì người dùng cũng phải xem được thông tin của chính mình qua
 * gateway, nên chấp nhận cả internal key lẫn JWT chính chủ.
 */
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Api-Key";

    // Chỉ service nội bộ được gọi, JWT người dùng không thay thế được
    private static final Map<String, List<String>> INTERNAL_ONLY_ROUTES = Map.of(
            "POST", List.of("/api/auth/users/*/debit", "/api/auth/users/*/credit")
    );

    // Chấp nhận internal key (service gọi service) HOẶC JWT của chính chủ -
    // AuthController.enforceOwnershipOrInternal() tự kiểm tra quyền sở hữu khi là JWT
    private static final Map<String, List<String>> INTERNAL_OR_OWNER_ROUTES = Map.of(
            "GET", List.of("/api/auth/users/*")
    );

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
                rejectAsInternal(response);
                return;
            }
            authenticateAsInternalService();
        } else if (matches(INTERNAL_OR_OWNER_ROUTES, method, path)) {
            String providedKey = request.getHeader(HEADER_NAME);
            if (providedKey != null) {
                if (!constantTimeEquals(providedKey, internalApiKey)) {
                    rejectAsInternal(response);
                    return;
                }
                authenticateAsInternalService();
            }
            // Không có key -> không chặn ở đây, để AuthTokenFilter xác thực bằng JWT
            // rồi AuthController tự kiểm tra người gọi có phải chủ tài khoản không.
        }

        filterChain.doFilter(request, response);
    }

    private boolean matches(Map<String, List<String>> routes, String method, String path) {
        return routes.getOrDefault(method, List.of()).stream()
                .anyMatch(p -> pathMatcher.match(p, path));
    }

    private void authenticateAsInternalService() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "internal-service", null, Collections.emptyList()));
    }

    private void rejectAsInternal(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"message\":\"Endpoint nội bộ, không được gọi trực tiếp\"}");
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
