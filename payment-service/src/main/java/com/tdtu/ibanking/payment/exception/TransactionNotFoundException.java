package com.tdtu.ibanking.payment.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(UUID id) { super("Không tìm thấy giao dịch: " + id); }
}