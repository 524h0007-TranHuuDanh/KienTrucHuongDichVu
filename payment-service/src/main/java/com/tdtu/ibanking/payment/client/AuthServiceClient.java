package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

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
}