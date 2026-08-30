package com.tdtu.ibanking.auth.exception;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException() {
        super("Số dư không đủ");
    }

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
