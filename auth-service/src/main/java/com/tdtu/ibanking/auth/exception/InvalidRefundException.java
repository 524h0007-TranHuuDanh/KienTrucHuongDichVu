package com.tdtu.ibanking.auth.exception;

public class InvalidRefundException extends RuntimeException {

    public InvalidRefundException() {
        super("Không thể hoàn tiền: chưa từng có giao dịch trừ tiền (DEBIT) tương ứng");
    }

    public InvalidRefundException(String message) {
        super(message);
    }
}
