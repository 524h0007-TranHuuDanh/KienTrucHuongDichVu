package com.tdtu.ibanking.auth.exception;

import java.util.UUID;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID userId) {
        super("Không tìm thấy người dùng với id " + userId);
    }

    public UserNotFoundException(String message) {
        super(message);
    }
}
