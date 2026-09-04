package com.tdtu.ibanking.payment.dto;

import com.tdtu.ibanking.payment.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryItem {
    private UUID id;
    private UUID tuitionId;
    private BigDecimal amount;
    private TransactionStatus status;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
