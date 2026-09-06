package com.tdtu.ibanking.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;

import com.tdtu.ibanking.payment.dto.EmailMessage;
import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.exception.RateLimitExceededException;
import com.tdtu.ibanking.payment.exception.ServiceBusyException;
import com.tdtu.ibanking.payment.support.AbstractPaymentIT;

/**
 * Hạn mức gửi OTP: {@code RateLimiterService.MAX_REQUESTS_PER_HOUR = 3} cho mỗi user,
 * đếm bằng {@code INCR} trên Redis thật với TTL 1 giờ.
 *
 * <p>Điểm phải cẩn thận khi viết test này: {@code initiatePayment} có nhánh chống trùng
 * (P-17) — nếu cùng khoản học phí đang có transaction PENDING/PROCESSING mà OTP còn hạn,
 * nó TRẢ LẠI transaction cũ và {@code return} sớm, tức KHÔNG trừ quota. Nếu gọi 4 lần với
 * cùng một MSSV thì lần 2-4 rơi hết vào nhánh này và rate limiter không bao giờ bị chạm.
 * Vì vậy mỗi lần gọi ở đây dùng một MSSV / khoản học phí KHÁC nhau nhưng CÙNG một userId —
 * hạn mức tính theo user nên vẫn đúng thứ cần kiểm thử.
 */
class RateLimitTest extends AbstractPaymentIT {

    private static final BigDecimal AMOUNT = new BigDecimal("1000000.00");
    private static final BigDecimal BALANCE = new BigDecimal("50000000.00");

    private PaymentInitResponse initiate(UUID userId, String mssv) {
        when(tuitionServiceClient.getTuitionByMssv(mssv)).thenReturn(unpaidTuition(mssv, AMOUNT));
        when(authServiceClient.getUserInfo(userId)).thenReturn(userWithBalance(userId, BALANCE));
        return paymentService.initiatePayment(mssv, userId);
    }

    @Test
    @DisplayName("Gửi OTP lần 4 trong cùng 1 giờ bị chặn bởi RateLimitExceededException")
    void quaBaLanGuiOtpTrongMotGio_thiBiChan() {
        UUID userId = UUID.randomUUID();

        // 3 lần đầu phải thành công, mỗi lần một khoản học phí khác nhau
        // để không rơi vào nhánh chống trùng (P-17) mà thực sự trừ quota.
        for (int i = 1; i <= 3; i++) {
            PaymentInitResponse response = initiate(userId, "5210900" + i);
            assertThat(response.getTransactionId()).isNotNull();
            assertThat(transactionRepository.findById(response.getTransactionId()).orElseThrow().getStatus())
                    .isEqualTo(TransactionStatus.PENDING);
            assertThat(otpKeyExists(response.getTransactionId())).isTrue();
        }

        assertThat(transactionRepository.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(3);
        assertThat(rateLimiterService.hasOtpQuota(userId)).isFalse();

        // Lần 4, vẫn một khoản học phí mới -> chỉ có thể bị chặn bởi rate limiter.
        RateLimitExceededException ex = catchThrowableOfType(
                () -> initiate(userId, "52109004"), RateLimitExceededException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getMessage()).isEqualTo("Bạn đã gửi quá nhiều yêu cầu OTP. Vui lòng thử lại sau.");
        assertThat(ex.getRetryAfterSeconds()).isGreaterThan(0L).isLessThanOrEqualTo(3600L);

        // Bị chặn ngay đầu initiatePayment: không tạo thêm transaction nào.
        assertThat(transactionRepository.findByUserIdOrderByCreatedAtDesc(userId)).hasSize(3);
    }

    @Test
    @DisplayName("Quota chỉ bị trừ SAU KHI gửi OTP thành công: gửi mail lỗi thì không mất lượt")
    void guiOtpThatBai_thiKhongTruQuota() {
        UUID userId = UUID.randomUUID();

        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), any(EmailMessage.class));

        ServiceBusyException ex = catchThrowableOfType(
                () -> initiate(userId, "52109101"), ServiceBusyException.class);
        assertThat(ex).isNotNull();

        // Giao dịch bị khai tử, OTP bị dọn, và quan trọng nhất: quota còn nguyên.
        assertThat(transactionRepository.findByUserIdOrderByCreatedAtDesc(userId))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.getStatus()).isEqualTo(TransactionStatus.FAILED);
                    assertThat(t.getErrorMessage()).isEqualTo("Không gửi được mã OTP");
                    assertThat(otpKeyExists(t.getId())).isFalse();
                });
        assertThat(rateLimiterService.hasOtpQuota(userId)).isTrue();
    }
}
