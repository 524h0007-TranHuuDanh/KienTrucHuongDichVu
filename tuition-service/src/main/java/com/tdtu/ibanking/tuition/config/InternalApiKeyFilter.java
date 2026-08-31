package com.tdtu.ibanking.tuition.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
public class InternalApiKeyFilter {

    private static final String HEADER_NAME = "X-Internal-Api-Key";
    private static final String PROTECTED_PATTERN = "/api/tuition/*/mark-paid";

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Bean
    public FilterRegistrationBean<Filter> internalApiKeyFilterRegistration() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MarkPaidGuardFilter(internalApiKey));
        registration.addUrlPatterns("/api/tuition/*");
        registration.setOrder(1);
        return registration;
    }

    private static class MarkPaidGuardFilter implements Filter {
        private final AntPathMatcher matcher = new AntPathMatcher();
        private final String expectedKey;

        MarkPaidGuardFilter(String expectedKey) {
            this.expectedKey = expectedKey;
        }

        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            HttpServletResponse response = (HttpServletResponse) res;

            boolean isMarkPaid = "POST".equalsIgnoreCase(request.getMethod())
                    && matcher.match(PROTECTED_PATTERN, request.getRequestURI());

            if (isMarkPaid) {
                String providedKey = request.getHeader(HEADER_NAME);
                if (providedKey == null || !constantTimeEquals(providedKey, expectedKey)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"message\":\"Endpoint nội bộ, không được gọi trực tiếp\"}");
                    return;
                }
            }
            chain.doFilter(req, res);
        }

        private boolean constantTimeEquals(String a, String b) {
            if (a == null || b == null) return false;
            return MessageDigest.isEqual(
                    a.getBytes(StandardCharsets.UTF_8),
                    b.getBytes(StandardCharsets.UTF_8));
        }
    }
}