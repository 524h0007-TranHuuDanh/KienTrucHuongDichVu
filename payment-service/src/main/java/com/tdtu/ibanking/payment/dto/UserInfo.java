package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserInfo {
    private UUID id;
    private String email;
    private BigDecimal balance;
    private Integer version;
}