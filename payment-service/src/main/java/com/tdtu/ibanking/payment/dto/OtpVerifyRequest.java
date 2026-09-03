package com.tdtu.ibanking.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;
//sửa cho P12: thêm @NotBlank cho otp
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OtpVerifyRequest {
    @NotNull(message = "Thiếu mã giao dịch")
    private UUID transactionId;

    @NotBlank(message = "Vui lòng nhập mã OTP")
    @Pattern(regexp = "^[0-9]{6}$", message = "OTP phải là 6 chữ số")
    private String otp;
}