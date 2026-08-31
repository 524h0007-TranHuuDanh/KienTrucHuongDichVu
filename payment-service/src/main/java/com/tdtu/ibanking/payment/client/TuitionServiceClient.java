package com.tdtu.ibanking.payment.client;

import com.tdtu.ibanking.payment.dto.TuitionDetailInfo;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.UUID;

@Component
public class TuitionServiceClient {
    @Autowired
    private RestTemplate restTemplate;

    @Value("${internal.api-key}")
    private String internalApiKey;

    private static final String TUITION_SERVICE_URL = "http://tuition-service:8082";
    private static final String INTERNAL_KEY_HEADER = "X-Internal-Api-Key";

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

        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_KEY_HEADER, internalApiKey);
        HttpEntity<MarkPaidRequest> entity = new HttpEntity<>(body, headers);

        ResponseEntity<TuitionDetailInfo> resp =
                restTemplate.exchange(url, HttpMethod.POST, entity, TuitionDetailInfo.class);
        return resp.getBody();
    }

    private static class MarkPaidRequest {
        public UUID transactionId;
        public MarkPaidRequest(UUID transactionId) {
            this.transactionId = transactionId;
        }
    }
}