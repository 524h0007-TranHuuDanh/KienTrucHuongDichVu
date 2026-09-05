package com.tdtu.ibanking.payment.exception;

import lombok.Getter;

@Getter
public class InvalidOtpException extends RuntimeException {
    private final int remainingAttempts;

    public InvalidOtpException(String message, int remainingAttempts) {
        super(message);
        this.remainingAttempts = remainingAttempts;
    }
}
