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
 * Gọi sang account-service, nơi giữ số dư và sổ ledger.
 *
 * <p>debit/credit/getBalance nằm trên đường đi của request người dùng nên không retry:
 * lỗi thì trả lỗi ngay, đừng bắt người ta chờ. Riêng ensureAccount chỉ chạy lúc seed
 * dữ liệu demo khi khởi động nên có retry ngắn.
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
     * Idempotent: user đã có account mặc định thì account-service giữ nguyên, bỏ qua
     * initialBalance. Retry {@value #ENSURE_ACCOUNT_MAX_ATTEMPTS} lần cách nhau
     * {@value #ENSURE_ACCOUNT_RETRY_DELAY_MS}ms vì healthcheck trong docker-compose chỉ
     * nói container đã "healthy", không đảm bảo request đầu tiên đi được ngay.
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
