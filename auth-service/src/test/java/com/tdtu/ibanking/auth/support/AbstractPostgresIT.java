package com.tdtu.ibanking.auth.support;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.repository.UserRepository;

/**
 * Lớp cha cho mọi test chạm database.
 *
 * <p>Dùng Postgres thật qua Testcontainers (KHÔNG dùng H2) vì các hành vi đang được
 * kiểm thử phụ thuộc trực tiếp vào Postgres: {@code SELECT ... FOR UPDATE} của
 * {@code UserRepository.findByIdForUpdate} và ràng buộc
 * {@code UNIQUE(transaction_id, type)} trên bảng {@code balance_entries}.
 *
 * <p>Container là singleton {@code static}, khởi động một lần trong static block và
 * không bao giờ bị JUnit dừng giữa chừng, nên mọi lớp test dùng chung một container
 * và Spring context được cache lại (chỉ khởi động một lần cho cả module).
 *
 * <p>Cố tình KHÔNG đánh {@code @Transactional} lên các lớp test: test đồng thời chạy
 * trên nhiều thread, mỗi thread mở transaction riêng và commit thật; nếu test cha
 * rollback thì sẽ che mất dữ liệu các thread con đã commit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
public abstract class AbstractPostgresIT {

    static {
        // docker-java đi kèm Testcontainers 1.19.x mặc định đàm phán Docker API 1.32,
        // trong khi Docker Engine đời mới (>= 29) chỉ còn chấp nhận từ 1.40 trở lên và
        // trả HTTP 400 -> "Could not find a valid Docker environment".
        // Ghim 1.41 (Docker 20.10+) để chạy được trên cả máy cũ lẫn máy mới.
        // Không ghi đè nếu người dùng đã tự cấu hình.
        if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
            System.setProperty("api.version", "1.41");
        }
    }

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("authdb")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    /**
     * application.yml khai báo {@code internal.api-key: ${INTERNAL_API_KEY}} KHÔNG có
     * giá trị mặc định, nên context sẽ không khởi động được nếu không set ở đây.
     * jwt.secret tuy có mặc định nhưng vẫn set tường minh cho tất định.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("internal.api-key", () -> TEST_INTERNAL_API_KEY);
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("jwt.expiration", () -> "86400000");
    }

    public static final String TEST_INTERNAL_API_KEY = "test-internal-key";
    public static final String TEST_JWT_SECRET = "c2VjcmV0LWtleS1mb3ItaWJhbmtpbmctdGVzdC1vbmx5LTEyMzQ1Ng==";

    @Autowired
    protected UserRepository userRepository;

    /**
     * Tạo user RIÊNG cho từng test với username/email duy nhất (cả hai cột đều UNIQUE),
     * để không phụ thuộc và không làm bẩn hai user demo do DemoDataSeeder ghi mỗi lần
     * context khởi động (524h0088 / 524h0456).
     */
    protected User createUser(BigDecimal balance) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        User user = new User();
        user.setUsername("test-" + unique);
        user.setEmail("test-" + unique + "@example.com");
        user.setFullName("Test User " + unique.substring(0, 8));
        user.setPhone("0900000000");
        user.setPassword("{noop}irrelevant");
        user.setBalance(balance);
        return userRepository.save(user);
    }
}
