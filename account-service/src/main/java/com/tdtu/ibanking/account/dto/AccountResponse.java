package com.tdtu.ibanking.account.dto;

import com.tdtu.ibanking.account.entity.AccountStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/** Response đầy đủ của endpoint tạo account (POST .../accounts). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AccountResponse {
    private UUID accountId;
    private UUID userId;
    private String accountNumber;
    private BigDecimal balance;
    private String currency;
    private boolean isDefault;
    private AccountStatus status;
}
