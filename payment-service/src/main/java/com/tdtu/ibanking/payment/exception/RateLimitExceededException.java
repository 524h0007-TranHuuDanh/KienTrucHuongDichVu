package com.tdtu.ibanking.payment.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) { super(message); }
}