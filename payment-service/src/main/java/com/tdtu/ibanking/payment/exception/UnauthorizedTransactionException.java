package com.tdtu.ibanking.payment.exception;

public class UnauthorizedTransactionException extends RuntimeException {
    public UnauthorizedTransactionException() { super("Bạn không có quyền thực hiện giao dịch này"); }
}