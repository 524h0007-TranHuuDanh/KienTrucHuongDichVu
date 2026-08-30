package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.TuitionDetailInfo;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Component
public class TuitionServiceClient {
    @Autowired
    private RestTemplate restTemplate;

    private final String TUITION_SERVICE_URL = "http://tuition-service:8082";

    public TuitionInfo getTuitionByMssv(String mssv) {
        String url = TUITION_SERVICE_URL + "/api/tuition/" + mssv;
        try {
            return restTemplate.getForObject(url, TuitionInfo.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public TuitionDetailInfo getTuitionById(UUID id) {
        String url = TUITION_SERVICE_URL + "/api/tuition/id/" + id;
        try {
            return restTemplate.getForObject(url, TuitionDetailInfo.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    public TuitionDetailInfo markPaid(UUID tuitionId, UUID transactionId) {
        String url = TUITION_SERVICE_URL + "/api/tuition/" + tuitionId + "/mark-paid";
        MarkPaidRequest body = new MarkPaidRequest(transactionId);
        return restTemplate.postForObject(url, body, TuitionDetailInfo.class);
    }

    private static class MarkPaidRequest {
        public UUID transactionId;

        public MarkPaidRequest(UUID transactionId) {
            this.transactionId = transactionId;
        }
    }
}
