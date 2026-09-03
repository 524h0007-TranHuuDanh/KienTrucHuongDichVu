package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TuitionDetailInfo {
    private UUID id;
    private String mssv;
    private BigDecimal amount;
    private Boolean paid;
    private UUID transactionId;
}
