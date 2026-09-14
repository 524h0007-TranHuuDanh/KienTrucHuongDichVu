package com.tdtu.ibanking.auth.service;

import com.tdtu.ibanking.auth.client.AccountServiceClient;
import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.exception.UserNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Lớp mỏng proxy sang account-service (xem TODO_Account_Service.md Phase 4) - toàn bộ
 * logic trừ/cộng tiền, khoá chống race, idempotent theo transactionId giờ nằm ở
 * account-service (AccountBalanceService). auth-service chỉ gọi HTTP qua
 * AccountServiceClient và ráp lại đúng shape BalanceResponse{userId, balance} như cũ -
 * KHÔNG đổi contract public.
 */
@Service
public class BalanceService {

    @Autowired
    private AccountServiceClient accountServiceClient;

    public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        return callOrNotFound(userId, () -> accountServiceClient.debit(userId, amount, transactionId));
    }

    public BalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        return callOrNotFound(userId, () -> accountServiceClient.credit(userId, amount, transactionId));
    }

    public BalanceResponse getBalance(UUID userId) {
        return callOrNotFound(userId, () -> accountServiceClient.getBalance(userId));
    }

    /**
     * account-service trả 404 khi user chưa có account mặc định - map lại thành
     * UserNotFoundException để giữ đúng message/format {"message": ...} cũ của
     * auth-service (GlobalExceptionHandler.handleUserNotFound). Các lỗi 4xx/5xx khác
     * (409 số dư không đủ / giao dịch đã chốt / hoàn tiền không hợp lệ...) được
     * GlobalExceptionHandler.handleAccountServiceError xử lý chung, giữ nguyên status
     * code + message do account-service trả về.
     */
    private BalanceResponse callOrNotFound(UUID userId, Supplier<BalanceResponse> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new UserNotFoundException(userId);
        }
    }
}
