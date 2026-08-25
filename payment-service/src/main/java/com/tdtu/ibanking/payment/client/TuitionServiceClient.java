package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.TuitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class TuitionServiceClient {
    @Autowired
    private RestTemplate restTemplate;

    private final String TUITION_SERVICE_URL = "http://tuition-service:8082";

    public TuitionInfo getTuitionByMssv(String mssv) {
        String url = TUITION_SERVICE_URL + "/api/tuition/" + mssv;
        return restTemplate.getForObject(url, TuitionInfo.class);
    }
}