package com.tdtu.ibanking.tuition.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.tdtu.ibanking.tuition.util.JwtUtil;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    private static final String HEADER_NAME = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String token = parseToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null
                && jwtUtil.validateToken(token)) {
            try {
                UUID userId = jwtUtil.getUserIdFromToken(token);
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId.toString(), null, Collections.emptyList()));
            } catch (Exception e) {
                log.warn("Failed to set user authentication in security context", e);
            }
        }

        filterChain.doFilter(request, response);
    }

    private String parseToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER_NAME);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}