package com.tdtu.ibanking.payment.exception;

import lombok.Getter;

@Getter
public class RateLimitExceededException extends RuntimeException {
    private final long retryAfterSeconds;
    // OTP_REQUEST_LIMIT (xin OTP quá nhiều) hoặc OTP_ATTEMPT_LIMIT (nhập sai quá nhiều).
    private final String code;

    public RateLimitExceededException(String message, long retryAfterSeconds, String code) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.code = code;
    }
}
