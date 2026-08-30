package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.BalanceChangeRequest;
import com.tdtu.ibanking.payment.dto.BalanceResponse;
import com.tdtu.ibanking.payment.dto.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.UUID;

@Component
public class AuthServiceClient {
    @Autowired
    private RestTemplate restTemplate;

    private final String AUTH_SERVICE_URL = "http://auth-service:8081";

    public UserInfo getUserInfo(UUID userId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId;
        return restTemplate.getForObject(url, UserInfo.class);
    }

    public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId + "/debit";
        BalanceChangeRequest request = new BalanceChangeRequest(amount, transactionId);
        return restTemplate.postForObject(url, request, BalanceResponse.class);
    }

    public BalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        String url = AUTH_SERVICE_URL + "/api/auth/users/" + userId + "/credit";
        BalanceChangeRequest request = new BalanceChangeRequest(amount, transactionId);
        return restTemplate.postForObject(url, request, BalanceResponse.class);
    }
}
