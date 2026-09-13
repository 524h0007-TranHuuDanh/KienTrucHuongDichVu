package com.tdtu.ibanking.tuition.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.tdtu.ibanking.tuition.AbstractPostgresIT;

/**
 * Test lop HTTP + security chain (JwtAuthenticationFilter + InternalApiKeyFilter).
 *
 * <p>Khong dung row seed cho endpoint mark-paid: tu tao student + tuition rieng.
 */
@AutoConfigureMockMvc
class TuitionControllerIT extends AbstractPostgresIT {

    private static final String INTERNAL_HEADER = "X-Internal-Api-Key";

    @Autowired
    private MockMvc mockMvc;

    private String ownMssv;
    private UUID tuitionId;

    @BeforeEach
    void setUp() {
        ownMssv = uniqueMssv();
        insertStudent(ownMssv);
        tuitionId = insertUnpaidTuition(ownMssv, "HK1-2526", "2025-10-15", new BigDecimal("7000000.00"));
    }

    @AfterEach
    void tearDown() {
        deleteOwnData(ownMssv);
    }

    @Test
    @DisplayName("GET /api/tuition/{mssv} khong co JWT -> 401")
    void getUnpaid_withoutJwt_returns401() throws Exception {
        mockMvc.perform(get("/api/tuition/{mssv}", "524H0002"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/tuition/{id}/mark-paid khong co internal key -> 403")
    void markPaid_withoutInternalKey_returns403() throws Exception {
        mockMvc.perform(post("/api/tuition/{id}/mark-paid", tuitionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/tuition/{id}/mark-paid co internal key dung -> 200")
    void markPaid_withInternalKey_returns200() throws Exception {
        UUID transactionId = UUID.randomUUID();

        mockMvc.perform(post("/api/tuition/{id}/mark-paid", tuitionId)
                .header(INTERNAL_HEADER, TEST_INTERNAL_API_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionId\":\"" + transactionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tuitionId.toString()))
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.transactionId").value(transactionId.toString()));
    }
}
