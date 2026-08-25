package com.tdtu.ibanking.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class RateLimiterService {
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String OTP_REQUEST_LIMIT = "otp:request:";
    private static final String OTP_ATTEMPT_LIMIT = "otp:attempt:";
    private static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final int MAX_ATTEMPTS = 3;
    private static final int ATTEMPT_TTL_MINUTES = 5;

    public boolean canRequestOtp(UUID userId) {
        String key = OTP_REQUEST_LIMIT + userId;
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        if (count == null) {
            redisTemplate.opsForValue().set(key, 1, 1, TimeUnit.HOURS);
            return true;
        }
        if (count >= MAX_REQUESTS_PER_HOUR) return false;
        redisTemplate.opsForValue().increment(key);
        return true;
    }

    public boolean canAttemptOtp(UUID transactionId) {
        String key = OTP_ATTEMPT_LIMIT + transactionId;
        Integer count = (Integer) redisTemplate.opsForValue().get(key);
        if (count == null) {
            redisTemplate.opsForValue().set(key, 1, ATTEMPT_TTL_MINUTES, TimeUnit.MINUTES);
            return true;
        }
        return count < MAX_ATTEMPTS;
    }

    public void recordFailedAttempt(UUID transactionId) {
        String key = OTP_ATTEMPT_LIMIT + transactionId;
        redisTemplate.opsForValue().increment(key);
        redisTemplate.expire(key, ATTEMPT_TTL_MINUTES, TimeUnit.MINUTES);
    }

    public void clearAttempts(UUID transactionId) {
        redisTemplate.delete(OTP_ATTEMPT_LIMIT + transactionId);
    }
}