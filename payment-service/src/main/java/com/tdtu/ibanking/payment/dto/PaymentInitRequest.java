package com.tdtu.ibanking.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitRequest {
    @NotBlank
    @Pattern(regexp = "^[0-9]{6}$", message = "MSSV phải là 6 chữ số")
    private String mssv;
}