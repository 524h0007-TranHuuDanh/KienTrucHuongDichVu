package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.BalanceChangeRequest;
import com.tdtu.ibanking.payment.dto.BalanceResponse;
import com.tdtu.ibanking.payment.dto.UserInfo;
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
//sửa cho p22: getUserInfo dùng internal key thay vì relay JTW
@Component
public class AuthServiceClient {
    @Autowired
    private RestTemplate restTemplate;

    @Value("${internal.api-key}")
    private String internalApiKey;

    private static final String AUTH_SERVICE_URL = "http://auth-service:8081";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

    public UserInfo getUserInfo(UUID userId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId;
        HttpEntity<Void> entity = new HttpEntity<>(internalHeaders());
        ResponseEntity<UserInfo> resp = restTemplate.exchange(url, HttpMethod.GET, entity, UserInfo.class);
        return resp.getBody();
    }

    public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId + "/debit";
        BalanceChangeRequest body = new BalanceChangeRequest(amount, transactionId);
        HttpEntity<BalanceChangeRequest> entity = new HttpEntity<>(body, internalHeaders());
        ResponseEntity<BalanceResponse> resp =
                restTemplate.exchange(url, HttpMethod.POST, entity, BalanceResponse.class);
        return resp.getBody();
    }

    public BalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId + "/credit";
        BalanceChangeRequest body = new BalanceChangeRequest(amount, transactionId);
        HttpEntity<BalanceChangeRequest> entity = new HttpEntity<>(body, internalHeaders());
        ResponseEntity<BalanceResponse> resp =
                restTemplate.exchange(url, HttpMethod.POST, entity, BalanceResponse.class);
        return resp.getBody();
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_KEY_HEADER, internalApiKey);
        return headers;
    }
}