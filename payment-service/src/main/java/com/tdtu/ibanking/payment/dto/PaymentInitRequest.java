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
    @Pattern(regexp = "^[0-9]{3}[A-Za-z][0-9]{4}$", message = "MSSV không đúng định dạng (VD: 524H0088)")
    private String mssv;
}