package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSuccessResponse {
    private UUID transactionId;
    private String status;
    private BigDecimal amountPaid;
    private BigDecimal remainingBalance;
    private LocalDateTime completedAt;
    private String message;
}