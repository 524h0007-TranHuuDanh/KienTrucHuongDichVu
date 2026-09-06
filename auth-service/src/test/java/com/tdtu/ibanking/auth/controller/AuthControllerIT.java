package com.tdtu.ibanking.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;

import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.security.JwtUtils;
import com.tdtu.ibanking.auth.support.AbstractPostgresIT;

import java.util.Collections;

/**
 * Kiểm thử tầng HTTP của auth-service qua MockMvc, chạy trên Postgres thật.
 * Bao gồm cả hai lớp bảo vệ: JWT (chủ sở hữu) và X-Internal-Api-Key (service nội bộ).
 */
class AuthControllerIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    /** Tạo JWT thật cho user, giống hệt cách AuthController phát hành khi login. */
    private String jwtFor(User user) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(), null, Collections.emptyList());
        return jwtUtils.generateJwtToken(authentication, user.getId());
    }

    @Test
    @DisplayName("POST /api/auth/login đúng thông tin -> 200 và body có accessToken")
    void loginWithValidCredentialsReturnsToken() throws Exception {
        // 524h0088 / 123456 do DemoDataSeeder ghi mỗi lần context khởi động (chỉ đọc, không sửa)
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"524h0088\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.userId").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/auth/login sai mật khẩu -> 401")
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"524h0088\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auth/users/{id} bằng JWT của người khác -> 403")
    void getOtherUserInfoWithForeignJwtReturns403() throws Exception {
        User caller = createUser(new BigDecimal("1000000"));
        User victim = createUser(new BigDecimal("2000000"));

        mockMvc.perform(get("/api/auth/users/{id}", victim.getId())
                        .header("Authorization", "Bearer " + jwtFor(caller)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/debit không có X-Internal-Api-Key -> 403")
    void debitWithoutInternalApiKeyReturns403() throws Exception {
        User user = createUser(new BigDecimal("1000000"));
        String body = "{\"amount\":1000,\"transactionId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post("/api/auth/users/{id}/debit", user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }
}
