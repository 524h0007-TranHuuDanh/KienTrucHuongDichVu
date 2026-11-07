package com.tdtu.ibanking.payment.service;

import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Chốt các giao dịch bị bỏ dở: người dùng bấm Huỷ, đóng tab hoặc để OTP hết hạn thì
 * không có request nào báo về, giao dịch sẽ nằm PENDING mãi và lịch sử hiện "chờ OTP"
 * vĩnh viễn. Job này chuyển chúng sang FAILED sau khi OTP chắc chắn đã hết hạn.
 *
 * <p>Chỉ đụng PENDING. PROCESSING nghĩa là tiền có thể đã bị trừ, phải đối soát,
 * không được tự đóng.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class PendingTransactionExpiryJob {

    static final String EXPIRED_MESSAGE = "Hết hạn OTP, không xác nhận trong thời gian quy định";

    // OTP sống 5 phút tính từ ngay sau lúc lưu giao dịch; chờ thêm 1 phút cho chắc.
    private static final int EXPIRE_AFTER_MINUTES = 6;

    private final TransactionRepository transactionRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RateLimiterService rateLimiterService;

    @Scheduled(initialDelayString = "${payment.expiry-sweep-ms:60000}",
               fixedDelayString = "${payment.expiry-sweep-ms:60000}")
    public void expireStalePending() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EXPIRE_AFTER_MINUTES);
        int expired = 0;
        for (Transaction t : transactionRepository.findByStatusAndCreatedAtBefore(TransactionStatus.PENDING, cutoff)) {
            // Còn khoá OTP thì vẫn xác thực được (lệch đồng hồ giữa DB và Redis) -> để lần quét sau.
            if (Boolean.TRUE.equals(redisTemplate.hasKey("otp:" + t.getId()))) {
                continue;
            }
            if (transactionRepository.failIfPending(t.getId(), EXPIRED_MESSAGE, LocalDateTime.now()) == 1) {
                rateLimiterService.clearAttempts(t.getId());
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Đã chốt FAILED {} giao dịch PENDING quá hạn OTP", expired);
        }
    }
}
