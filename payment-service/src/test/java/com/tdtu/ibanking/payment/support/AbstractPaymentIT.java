package com.tdtu.ibanking.payment.support;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.tdtu.ibanking.payment.client.AuthServiceClient;
import com.tdtu.ibanking.payment.client.TuitionServiceClient;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import com.tdtu.ibanking.payment.dto.UserInfo;
import com.tdtu.ibanking.payment.repository.TransactionRepository;
import com.tdtu.ibanking.payment.service.PaymentService;
import com.tdtu.ibanking.payment.service.RateLimiterService;

/**
 * Lớp cha cho mọi test của payment-service.
 *
 * <p>Postgres THẬT + Redis THẬT qua Testcontainers (không H2, không embedded/mock Redis):
 * khoá phân tán Redisson trong {@code verifyOtpAndPay} và bộ đếm rate-limit
 * ({@code INCR} + {@code EXPIRE}) chính là thứ đang được kiểm thử, mock đi thì test vô nghĩa.
 *
 * <p>RabbitMQ KHÔNG dựng container: {@code RabbitTemplate} được {@code @MockBean}.
 * Hai client HTTP sang auth-service/tuition-service cũng là {@code @MockBean}
 * vì đây là test của riêng payment-service.
 *
 * <p>Container là {@code static} và được start trong static block nên JUnit không dừng
 * giữa chừng: mọi lớp test dùng chung một cặp container và một Spring context (context cache).
 *
 * <p>Cố tình KHÔNG đánh {@code @Transactional}: {@code PaymentService} commit thật ở nhiều
 * bước của saga, rollback ở tầng test sẽ che mất đúng thứ cần khẳng định.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPaymentIT {

    /*
     * Testcontainers 1.19.7 (version do spring-boot-dependencies 3.2.4 quản lý) đi kèm
     * docker-java thương lượng Docker API 1.32, trong khi Docker Engine >= 29 chỉ chấp nhận
     * từ 1.40 trở lên -> mọi lệnh gọi trả 400 và Testcontainers báo
     * "Could not find a valid Docker environment".
     *
     * Ép api.version = 1.41 (tương ứng Docker 20.10+, vẫn chạy được với engine cũ).
     * Phải đặt TRƯỚC khi container được start ở static block bên dưới.
     */
    static {
        if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
            System.setProperty("api.version", "1.41");
        }
    }

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("paymentdb")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(false);

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** Đủ 32 byte cho HMAC-SHA256 mà {@code JwtUtil} dựng key. */
    public static final String TEST_JWT_SECRET =
            "test-jwt-secret-for-payment-service-integration-tests-0123456789";
    public static final String TEST_INTERNAL_API_KEY = "test-internal-key";

    /**
     * {@code application.yml} khai báo {@code jwt.secret: ${JWT_SECRET}} và
     * {@code internal.api-key: ${INTERNAL_API_KEY}} KHÔNG có giá trị mặc định,
     * thiếu là context không khởi động được.
     *
     * <p>{@code spring.data.redis.*} vừa cho Spring Data Redis vừa cho
     * {@code RedissonConfig} (nó đọc đúng ba property này qua {@code @Value}).
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.show-sql", () -> "false");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");

        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("internal.api-key", () -> TEST_INTERNAL_API_KEY);
    }

    protected static final String OTP_PREFIX = "otp:";

    @MockBean
    protected AuthServiceClient authServiceClient;

    @MockBean
    protected TuitionServiceClient tuitionServiceClient;

    @MockBean
    protected RabbitTemplate rabbitTemplate;

    @Autowired
    protected PaymentService paymentService;

    @Autowired
    protected RateLimiterService rateLimiterService;

    @Autowired
    protected TransactionRepository transactionRepository;

    @Autowired
    protected RedisTemplate<String, Object> redisTemplate;

    /**
     * Không có @Transactional nên phải tự dọn: xoá hết transaction đã commit và FLUSHALL Redis.
     * Flush Redis là bắt buộc, nếu không bộ đếm rate-limit của test trước rò sang test sau
     * (đặc biệt {@code otp:request:{userId}} với TTL 1 giờ).
     */
    @BeforeEach
    void resetState() {
        transactionRepository.deleteAll();
        try (RedisConnection connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushAll();
        }
    }

    protected TuitionInfo unpaidTuition(String mssv, BigDecimal amount) {
        return new TuitionInfo(UUID.randomUUID(), mssv, "Nguyen Van A", amount, false);
    }

    protected UserInfo userWithBalance(UUID userId, BigDecimal balance) {
        return new UserInfo(userId, "student@tdtu.edu.vn", balance, 0);
    }

    /** Đọc OTP thật mà service vừa ghi vào Redis (cùng RedisTemplate/serializer với production). */
    protected String readOtp(UUID transactionId) {
        return (String) redisTemplate.opsForValue().get(OTP_PREFIX + transactionId);
    }

    protected boolean otpKeyExists(UUID transactionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(OTP_PREFIX + transactionId));
    }

    protected long otpTtlSeconds(UUID transactionId) {
        Long ttl = redisTemplate.getExpire(OTP_PREFIX + transactionId, TimeUnit.SECONDS);
        return ttl == null ? -2L : ttl;
    }

    /** Một mã 6 chữ số chắc chắn KHÁC mã thật, để không "trúng tủ" ngẫu nhiên. */
    protected String wrongOtpFor(String realOtp) {
        return "000000".equals(realOtp) ? "111111" : "000000";
    }
}
