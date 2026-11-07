package com.tdtu.ibanking.payment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitResponse {
    private UUID transactionId;
    private UUID tuitionId;
    private String semester;
    private BigDecimal amount;
    private BigDecimal balance;
    // Hạn thật của OTP. Gọi initiate lại khi OTP cũ còn hạn thì backend trả lại đúng
    // giao dịch cũ (không gửi mã mới), nên client phải đếm ngược theo mốc này chứ
    // không tự cộng 5 phút từ lúc nhận response.
    private OffsetDateTime otpExpiresAt;
}