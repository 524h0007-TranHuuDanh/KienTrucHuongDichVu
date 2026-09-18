package com.tdtu.ibanking.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Ba hạn mức quanh OTP, tất cả đếm trên Redis: số lần xin OTP theo giờ (mỗi user),
 * số lần nhập sai của một giao dịch, và tổng số lần nhập sai theo giờ của một user —
 * cái cuối để người dùng không lách hạn mức bằng cách tạo giao dịch mới liên tục.
 */
@Service
@RequiredArgsConstructor
public class RateLimiterService {
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String OTP_REQUEST_LIMIT = "otp:request:";
    private static final String OTP_ATTEMPT_LIMIT = "otp:attempt:";
    private static final String OTP_USER_FAIL_LIMIT = "otp:userfail:";

    private static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_USER_FAILS_PER_HOUR = 8;
    private static final int ATTEMPT_TTL_MINUTES = 5;

    // Xem và trừ lượt là hai bước riêng: PaymentService chỉ trừ sau khi email đã đi.
    public boolean hasOtpQuota(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        return count == null || count < MAX_REQUESTS_PER_HOUR;
    }

    public void consumeOtpQuota(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        // INCR atomic, khỏi lo hai request song song cùng ghi đè lên một giá trị đọc trước.
        Long newCount = redisTemplate.opsForValue().increment(key);
        if (newCount != null && newCount == 1L) {
            redisTemplate.expire(key, 1, TimeUnit.HOURS);
        }
    }

    public void refundOtpRequest(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Long v = redisTemplate.opsForValue().decrement(key);
        if (v != null && v < 0) redisTemplate.opsForValue().set(key, 0);
    }

    public boolean canAttemptOtp(UUID transactionId, UUID userId) {
        String userKey = OTP_USER_FAIL_LIMIT + userId;
        Integer userFails = (Integer) redisTemplate.opsForValue().get(userKey);
        if (userFails != null && userFails >= MAX_USER_FAILS_PER_HOUR) return false;

        String key = OTP_ATTEMPT_LIMIT + transactionId;
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        return count == null || count < MAX_ATTEMPTS;
    }

    public void recordFailedAttempt(UUID transactionId, UUID userId) {
        String key = OTP_ATTEMPT_LIMIT + transactionId;
        Long newCount = redisTemplate.opsForValue().increment(key);
        if (newCount != null && newCount == 1L) {
            redisTemplate.expire(key, ATTEMPT_TTL_MINUTES, TimeUnit.MINUTES);
        }

        String userKey = OTP_USER_FAIL_LIMIT + userId;
        Long newUserCount = redisTemplate.opsForValue().increment(userKey);
        if (newUserCount != null && newUserCount == 1L) {
            redisTemplate.expire(userKey, 1, TimeUnit.HOURS);
        }
    }

    public void clearAttempts(UUID transactionId) {
        redisTemplate.delete(OTP_ATTEMPT_LIMIT + transactionId);
    }

    public void clearUserFails(UUID userId) {
        redisTemplate.delete(OTP_USER_FAIL_LIMIT + userId);
    }

    // Hai hàm dưới cho FE hiển thị "còn mấy lần thử" và "chờ bao lâu".

    public int getRemainingAttempts(UUID transactionId) {
        Integer count = (Integer) redisTemplate.opsForValue().get(OTP_ATTEMPT_LIMIT + transactionId);
        int used = count == null ? 0 : count;
        return Math.max(0, MAX_ATTEMPTS - used);
    }

    public long getOtpRequestRetryAfterSeconds(UUID userId) {
        return ttlSeconds(OTP_REQUEST_LIMIT + userId);
    }

    public long getOtpAttemptRetryAfterSeconds(UUID transactionId, UUID userId) {
        // Bị chặn bởi một trong hai bộ đếm, nên phải chờ cái lâu hơn mới thử lại được.
        return Math.max(
                ttlSeconds(OTP_ATTEMPT_LIMIT + transactionId),
                ttlSeconds(OTP_USER_FAIL_LIMIT + userId));
    }

    private long ttlSeconds(String key) {
        Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        return ttl != null && ttl > 0 ? ttl : 0;
    }
}