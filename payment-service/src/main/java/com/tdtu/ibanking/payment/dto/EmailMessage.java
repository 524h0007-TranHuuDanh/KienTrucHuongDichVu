package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailMessage implements Serializable {
    private static final long serialVersionUID = 1L;
    private String to;
    private String type;              // "OTP" hoặc "PAYMENT_SUCCESS"
    private Map<String, String> variables;
}