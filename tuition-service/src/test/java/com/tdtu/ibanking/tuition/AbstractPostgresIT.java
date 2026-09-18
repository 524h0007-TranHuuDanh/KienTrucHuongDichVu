package com.tdtu.ibanking.tuition;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base cho toan bo test cua tuition-service.
 *
 * <p>Chay tren Postgres that (testcontainers) chu khong phai H2, vi:
 * <ul>
 *   <li>{@code TuitionRepository.findFirstUnpaid} la native query dung {@code LIMIT 1};</li>
 *   <li>{@code findByIdForUpdate} dua tren {@code SELECT ... FOR UPDATE} - H2 co ngu nghia
 *       khoa khac nen test concurrency se pass ngay ca khi da go bo khoa (xanh gia).</li>
 * </ul>
 *
 * <p>Container la static singleton (khoi tao 1 lan trong static block, khong dung
 * {@code @Container} de JUnit khong stop container sau moi class) -> ca 3 test class
 * dung chung 1 database va Spring context duoc cache.
 *
 * <p>khong dat {@code @Transactional} len test class: markPaid va test concurrency phai
 * commit that su thi khoa pessimistic moi co y nghia.
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIT {

    /** Khoa noi bo dung cho test - phai khop voi header X-Internal-Api-Key trong test controller. */
    public static final String TEST_INTERNAL_API_KEY = "test-internal-key";

    /** >= 32 byte, neu khong Keys.hmacShaKeyFor() se nem WeakKeyException khi JwtUtil khoi tao key. */
    public static final String TEST_JWT_SECRET =
            "tuition-service-test-jwt-secret-key-0123456789-abcdefghijklmnop";

    static {
        // Docker Engine >= 29 co MinAPIVersion 1.40 va tra HTTP 400 cho cac API version
        // cu hon ma docker-java (di kem Testcontainers 1.19.7) mac dinh negotiate
        // -> "Could not find a valid Docker environment".
        // Ghim api.version = 1.41 (Docker 20.10+) truoc khi container duoc khoi tao.
        if (System.getProperty("api.version") == null && System.getenv("DOCKER_API_VERSION") == null) {
            System.setProperty("api.version", "1.41");
        }
    }

    @SuppressWarnings("resource")
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("tuitiondb")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // 10 luong cua test concurrency, moi luong giu 1 connection trong suot transaction.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
        // application.yml khai bao ${JWT_SECRET} / ${INTERNAL_API_KEY} khong co default
        // -> thieu 2 property nay thi context khong khoi dong duoc.
        registry.add("jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("internal.api-key", () -> TEST_INTERNAL_API_KEY);
    }

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------
    // Helper tao/xoa du lieu rieng cua test.
    //
    // data.sql chay lai moi lan khoi dong (spring.sql.init.mode: always) va seed
    // 524H0001..524H0005 voi id co dinh. Test chi ĐOC moi duoc dung du lieu seed;
    // moi test co GHI (markPaid, concurrency) phai tu tao row rieng voi id
    // UUID.randomUUID() va mssv duy nhat, roi tu don dep - khong truncate bang.
    // ---------------------------------------------------------------------

    /** Sinh mssv duy nhat, do dai <= 16 (cot students.mssv la varchar(16), UNIQUE). */
    protected static String uniqueMssv() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 11).toUpperCase();
    }

    /** Insert 1 student rieng cua test. Phai insert TRUOC tuition vi tuitions.mssv la FK. */
    protected void insertStudent(String mssv) {
        jdbcTemplate.update(
                "INSERT INTO students (id, mssv, full_name, email, faculty, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, now())",
                UUID.randomUUID(), mssv, "Test Student " + mssv, mssv + "@test.local", "Test Faculty");
    }

    /** Insert 1 khoan hoc phi CHUA dong, tra ve id (UUID.randomUUID()) cua no. */
    protected UUID insertUnpaidTuition(String mssv, String semester, String dueDate, BigDecimal amount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tuitions (id, mssv, semester, due_date, amount, paid, paid_at, "
                        + "transaction_id, version, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS date), ?, false, NULL, NULL, 0, now())",
                id, mssv, semester, dueDate, amount);
        return id;
    }

    /** Don dep DUY NHAT du lieu do test nay tao ra. Tuyet doi khong truncate. */
    protected void deleteOwnData(String mssv) {
        if (mssv == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM tuitions WHERE mssv = ?", mssv);
        jdbcTemplate.update("DELETE FROM students WHERE mssv = ?", mssv);
    }
}
