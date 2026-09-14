package com.tdtu.ibanking.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Body gửi sang account-service ở POST /api/account/users/{userId}/accounts
 * (dùng bởi AccountServiceClient.ensureAccount, chủ yếu cho DemoDataSeeder).
 * Mirror đúng account-service/dto/CreateAccountRequest.java - initialBalance optional,
 * chỉ áp dụng khi account mặc định của user CHƯA tồn tại (endpoint idempotent).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {
    private BigDecimal initialBalance;
}
