package com.tdtu.ibanking.auth.controller;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.security.JwtUtils;
import com.tdtu.ibanking.auth.support.AbstractPostgresIT;

/**
 * Kiểm thử tầng HTTP của auth-service qua MockMvc, chạy trên Postgres thật.
 * Bao gồm cả hai lớp bảo vệ: JWT (chủ sở hữu) và X-Internal-Api-Key (service nội bộ).
 *
 * <p>Số dư nằm ở account-service, nên login/getUserInfo/debit/credit đều phải gọi HTTP
 * sang đó. Ở đây dùng {@link MockRestServiceServer} thay vì mock hẳn
 * {@code AccountServiceClient}, để test chạy qua cả tầng serialize/deserialize JSON và
 * bắt được thay đổi shape của response trả ra ngoài.
 */
class AuthControllerIT extends AbstractPostgresIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RestTemplate restTemplate;

    @Value("${account-service.base-url}")
    private String accountServiceBaseUrl;

    private MockRestServiceServer mockAccountService;

    @BeforeEach
    void bindMockAccountService() {
        mockAccountService = MockRestServiceServer.createServer(restTemplate);
    }

    @AfterEach
    void verifyAllExpectedCallsMade() {
        mockAccountService.verify();
    }

    /** Tạo JWT thật cho user, giống hệt cách AuthController phát hành khi login. */
    private String jwtFor(User user) {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                user.getUsername(), null, Collections.emptyList());
        return jwtUtils.generateJwtToken(authentication, user.getId());
    }

    private String balanceUrl(UUID userId) {
        return accountServiceBaseUrl + "/api/account/users/" + userId + "/balance";
    }

    private String balanceJson(UUID userId, String balance) {
        return "{\"accountId\":\"" + UUID.randomUUID() + "\",\"userId\":\"" + userId
                + "\",\"balance\":" + balance + "}";
    }

    @Test
    @DisplayName("POST /api/auth/login đúng thông tin -> 200, body giữ nguyên shape {accessToken,userId,email,balance}")
    void loginWithValidCredentialsReturnsToken() throws Exception {
        // 524h0088 / 123456 do DemoDataSeeder ghi mỗi lần context khởi động (chỉ đọc, không sửa)
        User seededUser = userRepository.findByUsername("524h0088").orElseThrow();

        mockAccountService.expect(requestTo(balanceUrl(seededUser.getId())))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY))
                .andRespond(withSuccess(
                        balanceJson(seededUser.getId(), "100000000"), MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"524h0088\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.userId").value(seededUser.getId().toString()))
                .andExpect(jsonPath("$.email").value(seededUser.getEmail()))
                .andExpect(jsonPath("$.balance").value(100000000));
    }

    @Test
    @DisplayName("POST /api/auth/login sai mật khẩu -> 401 (không gọi account-service)")
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"524h0088\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/auth/users/{id} bằng JWT của người khác -> 403 (không gọi account-service)")
    void getOtherUserInfoWithForeignJwtReturns403() throws Exception {
        User caller = createUser();
        User victim = createUser();

        mockMvc.perform(get("/api/auth/users/{id}", victim.getId())
                        .header("Authorization", "Bearer " + jwtFor(caller)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/auth/users/{id} bằng X-Internal-Api-Key -> 200, body giữ nguyên field id/email/fullName/phone/balance")
    void getUserInfoWithInternalKeyReturnsSameShape() throws Exception {
        User user = createUser();

        mockAccountService.expect(requestTo(balanceUrl(user.getId())))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        balanceJson(user.getId(), "2000000"), MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/auth/users/{id}", user.getId())
                        .header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.fullName").value(user.getFullName()))
                .andExpect(jsonPath("$.phone").value(user.getPhone()))
                .andExpect(jsonPath("$.balance").value(2000000));
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/debit không có X-Internal-Api-Key -> 403 (không gọi account-service)")
    void debitWithoutInternalApiKeyReturns403() throws Exception {
        User user = createUser();
        String body = "{\"amount\":1000,\"transactionId\":\"" + UUID.randomUUID() + "\"}";

        mockMvc.perform(post("/api/auth/users/{id}/debit", user.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/debit với X-Internal-Api-Key -> 200, body giữ nguyên shape BalanceResponse{userId,balance}")
    void debitWithInternalApiKeyProxiesToAccountService() throws Exception {
        User user = createUser();
        UUID transactionId = UUID.randomUUID();
        String debitUrl = accountServiceBaseUrl + "/api/account/users/" + user.getId() + "/debit";

        mockAccountService.expect(requestTo(debitUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY))
                .andRespond(withSuccess(
                        balanceJson(user.getId(), "900000"), MediaType.APPLICATION_JSON));

        String body = "{\"amount\":100000,\"transactionId\":\"" + transactionId + "\"}";
        mockMvc.perform(post("/api/auth/users/{id}/debit", user.getId())
                        .header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.balance").value(900000));
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/credit với X-Internal-Api-Key -> 200, body giữ nguyên shape BalanceResponse{userId,balance}")
    void creditWithInternalApiKeyProxiesToAccountService() throws Exception {
        User user = createUser();
        UUID transactionId = UUID.randomUUID();
        String creditUrl = accountServiceBaseUrl + "/api/account/users/" + user.getId() + "/credit";

        mockAccountService.expect(requestTo(creditUrl))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY))
                .andRespond(withSuccess(
                        balanceJson(user.getId(), "1100000"), MediaType.APPLICATION_JSON));

        String body = "{\"amount\":100000,\"transactionId\":\"" + transactionId + "\"}";
        mockMvc.perform(post("/api/auth/users/{id}/credit", user.getId())
                        .header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(user.getId().toString()))
                .andExpect(jsonPath("$.balance").value(1100000));
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/debit khi account-service trả 409 (số dư không đủ) -> auth-service giữ nguyên 409 + message")
    void debitWhenAccountServiceReturnsConflictPropagatesStatusAndMessage() throws Exception {
        User user = createUser();
        UUID transactionId = UUID.randomUUID();
        String debitUrl = accountServiceBaseUrl + "/api/account/users/" + user.getId() + "/debit";

        mockAccountService.expect(requestTo(debitUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Số dư không đủ\"}"));

        String body = "{\"amount\":100000,\"transactionId\":\"" + transactionId + "\"}";
        mockMvc.perform(post("/api/auth/users/{id}/debit", user.getId())
                        .header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Số dư không đủ"));
    }

    @Test
    @DisplayName("POST /api/auth/users/{id}/debit khi account-service trả 404 (chưa có account) -> auth-service trả 404 + message")
    void debitWhenAccountServiceReturnsNotFoundMapsToUserNotFound() throws Exception {
        User user = createUser();
        UUID transactionId = UUID.randomUUID();
        String debitUrl = accountServiceBaseUrl + "/api/account/users/" + user.getId() + "/debit";

        mockAccountService.expect(requestTo(debitUrl))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Không tìm thấy account mặc định cho user\"}"));

        String body = "{\"amount\":100000,\"transactionId\":\"" + transactionId + "\"}";
        mockMvc.perform(post("/api/auth/users/{id}/debit", user.getId())
                        .header("X-Internal-Api-Key", TEST_INTERNAL_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").isString());
    }
}
