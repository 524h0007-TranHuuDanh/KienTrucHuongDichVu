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
 * Lớp proxy mỏng sang account-service. Trừ/cộng tiền, khoá chống race và idempotent
 * theo transactionId đều nằm bên đó; ở đây chỉ gọi HTTP rồi ráp lại đúng shape
 * BalanceResponse cũ để contract public không đổi.
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
     * 404 từ account-service nghĩa là user chưa có account mặc định — đổi thành
     * UserNotFoundException để body lỗi giữ nguyên format cũ. Các mã 4xx/5xx còn lại
     * đi thẳng qua GlobalExceptionHandler, giữ nguyên status và message gốc.
     */
    private BalanceResponse callOrNotFound(UUID userId, Supplier<BalanceResponse> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.NotFound e) {
            throw new UserNotFoundException(userId);
        }
    }
}
