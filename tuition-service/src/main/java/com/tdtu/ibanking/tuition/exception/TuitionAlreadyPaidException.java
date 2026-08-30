package com.tdtu.ibanking.tuition.exception;

import java.util.UUID;

public class TuitionAlreadyPaidException extends RuntimeException {

    public TuitionAlreadyPaidException(String message) {
        super(message);
    }

    public TuitionAlreadyPaidException(UUID id) {
        super("Khoản học phí " + id + " đã được thanh toán bởi một giao dịch khác");
    }
}
