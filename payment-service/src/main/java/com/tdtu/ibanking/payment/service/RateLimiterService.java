package com.tdtu.ibanking.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
//sửa cho p09, p10, p18,p19
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

    // ===== P-18: tách CHECK và TRỪ lượt gửi OTP =====
    public boolean hasOtpQuota(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        return count == null || count < MAX_REQUESTS_PER_HOUR;
    }

    public void consumeOtpQuota(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Long newCount = redisTemplate.opsForValue().increment(key);   // P-10: atomic increment
        if (newCount != null && newCount == 1L) {
            redisTemplate.expire(key, 1, TimeUnit.HOURS);
        }
    }

    public void refundOtpRequest(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Long v = redisTemplate.opsForValue().decrement(key);
        if (v != null && v < 0) redisTemplate.opsForValue().set(key, 0);
    }

    // ===== P-09, P-19: sửa đếm lệch 1 đơn vị + thêm counter theo user =====
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
}