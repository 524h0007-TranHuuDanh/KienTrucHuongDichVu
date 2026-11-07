package com.tdtu.ibanking.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitRequest {
    @NotBlank
    @Pattern(regexp = "^[0-9]{3}[A-Za-z][0-9]{4}$", message = "MSSV không đúng định dạng (VD: 524H0001)")
    private String mssv;

    // Tuỳ chọn: khoản học phí client đang hiển thị. Backend tự chọn khoản đến hạn sớm
    // nhất theo MSSV, nên nếu khoản đó đã đổi (người khác vừa đóng) thì từ chối ngay
    // bằng TUITION_CHANGED, trước khi tạo giao dịch và gửi OTP.
    private UUID tuitionId;
}