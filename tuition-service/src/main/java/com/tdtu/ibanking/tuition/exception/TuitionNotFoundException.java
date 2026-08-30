package com.tdtu.ibanking.tuition.exception;

import java.util.UUID;

public class TuitionNotFoundException extends RuntimeException {

    public TuitionNotFoundException(String message) {
        super(message);
    }

    public TuitionNotFoundException(UUID id) {
        super("Không tìm thấy khoản học phí với id " + id);
    }
}
