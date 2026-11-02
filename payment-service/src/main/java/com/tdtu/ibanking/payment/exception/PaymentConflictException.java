package com.tdtu.ibanking.payment.exception;

import lombok.Getter;

/** Xung đột nghiệp vụ (409) không phải do thiếu số dư; {@code code} lấy từ {@link ErrorCode}. */
@Getter
public class PaymentConflictException extends RuntimeException {
    private final String code;

    public PaymentConflictException(String message, String code) {
        super(message);
        this.code = code;
    }
}
