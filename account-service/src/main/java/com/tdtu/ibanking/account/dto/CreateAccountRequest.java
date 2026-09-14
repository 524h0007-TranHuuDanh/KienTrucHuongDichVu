package com.tdtu.ibanking.account.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Body của POST /api/account/users/{userId}/accounts.
 *
 * <p>{@code initialBalance} là optional, chỉ áp dụng khi account mặc định CHƯA tồn
 * tại (dùng cho seeding demo data). Nếu user đã có account mặc định, field này bị
 * bỏ qua hoàn toàn - endpoint idempotent, KHÔNG ghi đè số dư hiện có.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    @DecimalMin(value = "0.0", inclusive = true)
    private BigDecimal initialBalance;
}
