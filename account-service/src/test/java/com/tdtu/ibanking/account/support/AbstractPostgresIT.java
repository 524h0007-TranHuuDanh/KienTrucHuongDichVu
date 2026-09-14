package com.tdtu.ibanking.account.support;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tdtu.ibanking.account.entity.Account;
import com.tdtu.ibanking.account.entity.AccountStatus;
import com.tdtu.ibanking.account.repository.AccountRepository;

/**
 * Lớp cha cho mọi test chạm database, copy nguyên cơ chế từ
 * {@code auth-service/.../support/AbstractPostgresIT.java} (đổi User -> Account,
 * authdb -> accountdb). KHÔNG được gỡ static block set {@code api.version=1.41} -
 * gỡ là Testcontainers không kết nối được Docker Engine trên máy này.
 *
 * <p>Dùng Postgres thật qua Testcontainers (KHÔNG dùng H2) vì các hành vi đang được
 * kiểm thử phụ thuộc trực tiếp vào Postgres: {@code SELECT ... FOR UPDATE} của
 * {@code AccountRepository.findDefaultByUserIdForUpdate} và ràng buộc
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
                    .withDatabaseName("accountdb")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withReuse(false);

    static {
        POSTGRES.start();
    }

    /**
     * application.yml khai báo {@code internal.api-key: ${INTERNAL_API_KEY}} KHÔNG có
     * giá trị mặc định, nên context sẽ không khởi động được nếu không set ở đây.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("internal.api-key", () -> TEST_INTERNAL_API_KEY);
    }

    public static final String TEST_INTERNAL_API_KEY = "test-internal-key";

    @Autowired
    protected AccountRepository accountRepository;

    /**
     * Tạo account mặc định RIÊNG cho từng test với userId/accountNumber ngẫu nhiên
     * (accountNumber UNIQUE), để không phụ thuộc và không làm bẩn dữ liệu demo do
     * DemoDataSeeder của auth-service tạo qua account-service khi seed.
     */
    protected Account createAccount(BigDecimal balance) {
        String unique = UUID.randomUUID().toString().replace("-", "");
        Account account = Account.builder()
                .userId(UUID.randomUUID())
                .accountNumber(unique.substring(0, 12))
                .balance(balance)
                .currency("VND")
                .isDefault(true)
                .status(AccountStatus.ACTIVE)
                .build();
        return accountRepository.save(account);
    }
}
