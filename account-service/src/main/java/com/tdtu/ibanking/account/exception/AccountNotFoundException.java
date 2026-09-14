package com.tdtu.ibanking.account.exception;

import java.util.UUID;

/**
 * account-service không có khái niệm "user không tồn tại" (đó là domain của
 * auth-service) - chỉ có "account không tồn tại", nên đổi tên so với
 * UserNotFoundException gốc của auth-service cho đúng domain.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountOrUserId) {
        super("Không tìm thấy account với id " + accountOrUserId);
    }

    public AccountNotFoundException(String message) {
        super(message);
    }

    public static AccountNotFoundException forUser(UUID userId) {
        return new AccountNotFoundException("Không tìm thấy account mặc định cho user " + userId);
    }
}
