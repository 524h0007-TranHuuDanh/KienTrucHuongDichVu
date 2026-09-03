package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BalanceChangeRequest {
    private BigDecimal amount;
    private UUID transactionId;
}
