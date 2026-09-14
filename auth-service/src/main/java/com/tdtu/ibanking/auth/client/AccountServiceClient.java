package com.tdtu.ibanking.auth.client;

import com.tdtu.ibanking.auth.dto.BalanceChangeRequest;
import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.dto.CreateAccountRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Client HTTP nội bộ gọi sang account-service (sở hữu balance/ledger sau khi tách khỏi
 * auth-service - xem TODO_Account_Service.md Phase 4). Copy đúng pattern
 * payment-service/client/AuthServiceClient.java: RestTemplate + header X-Internal-Api-Key,
 * base URL đọc qua config (KHÔNG hardcode).
 *
 * <p>debit/credit/getBalance là request thời gian thực từ người dùng - KHÔNG retry, lỗi
 * thì trả lỗi luôn để không làm chậm response. ensureAccount chỉ dùng lúc seed demo data
 * lúc khởi động (DemoDataSeeder) nên có retry/backoff ngắn để chịu được race lúc
 * account-service chưa sẵn sàng.
 */
@Component
public class AccountServiceClient {

    private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";
    private static final int ENSURE_ACCOUNT_MAX_ATTEMPTS = 3;
    private static final long ENSURE_ACCOUNT_RETRY_DELAY_MS = 500;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${internal.api-key}")
    private String internalApiKey;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    public BalanceResponse getBalance(UUID userId) {
        String url = accountServiceBaseUrl + "/api/account/users/" + userId + "/balance";
        HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
        ResponseEntity<BalanceResponse> resp =
                restTemplate.exchange(url, HttpMethod.GET, entity, BalanceResponse.class);
        return resp.getBody();
    }

    public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        return changeBalance(userId, amount, transactionId, "debit");
    }

    public BalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        return changeBalance(userId, amount, transactionId, "credit");
    }

    private BalanceResponse changeBalance(UUID userId, BigDecimal amount, UUID transactionId, String action) {
        String url = accountServiceBaseUrl + "/api/account/users/" + userId + "/" + action;
        BalanceChangeRequest body = new BalanceChangeRequest(amount, transactionId);
        HttpEntity<BalanceChangeRequest> entity = new HttpEntity<>(body, internalHeaders());
        ResponseEntity<BalanceResponse> resp =
                restTemplate.exchange(url, HttpMethod.POST, entity, BalanceResponse.class);
        return resp.getBody();
    }

    /**
     * Idempotent bên phía account-service: nếu user đã có account mặc định thì trả về
     * nguyên trạng, initialBalance bị bỏ qua. Retry {@value #ENSURE_ACCOUNT_MAX_ATTEMPTS}
     * lần / {@value #ENSURE_ACCOUNT_RETRY_DELAY_MS}ms - depends_on.condition: service_healthy
     * trong docker-compose chỉ đợi container start & healthcheck app-level của
     * account-service, không đảm bảo tuyệt đối request đầu tiên lúc DemoDataSeeder chạy
     * sẽ thành công ngay (race hiếm gặp lúc container vừa healthy).
     */
    public void ensureAccount(UUID userId, BigDecimal initialBalance) {
        String url = accountServiceBaseUrl + "/api/account/users/" + userId + "/accounts";
        CreateAccountRequest body = new CreateAccountRequest(initialBalance);
        HttpEntity<CreateAccountRequest> entity = new HttpEntity<>(body, internalHeaders());

        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= ENSURE_ACCOUNT_MAX_ATTEMPTS; attempt++) {
            try {
                restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
                return;
            } catch (RuntimeException e) {
                lastError = e;
                if (attempt < ENSURE_ACCOUNT_MAX_ATTEMPTS) {
                    sleep(ENSURE_ACCOUNT_RETRY_DELAY_MS);
                }
            }
        }
        throw lastError;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_KEY_HEADER, internalApiKey);
        return headers;
    }
}
