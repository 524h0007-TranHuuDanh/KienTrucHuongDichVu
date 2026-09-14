package com.tdtu.ibanking.account.exception;

public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException() {
        super("Số dư không đủ");
    }

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
