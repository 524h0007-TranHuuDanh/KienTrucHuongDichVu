package com.tdtu.ibanking.auth.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate dùng cho AccountServiceClient (gọi service-to-service tới account-service).
 * Timeout ngắn vì đây là lời gọi nội bộ trong cùng docker network - copy pattern từ
 * payment-service/config/RestTemplateConfig.java.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(2))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
}
