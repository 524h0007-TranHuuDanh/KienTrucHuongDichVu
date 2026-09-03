package com.tdtu.ibanking.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceResponse {
    private UUID userId;
    private BigDecimal balance;
}
