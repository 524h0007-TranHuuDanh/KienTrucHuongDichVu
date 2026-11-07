package com.tdtu.ibanking.payment.exception;

public class TuitionNotFoundException extends RuntimeException {
    public TuitionNotFoundException(String mssv) {
        super("Không tìm thấy khoản học phí chưa đóng cho MSSV: " + mssv);
    }
}
