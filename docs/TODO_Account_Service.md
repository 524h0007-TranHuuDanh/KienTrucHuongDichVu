# TODO — Tách `account-service` ra khỏi `auth-service`

> File theo dõi tiến trình, làm xuyên nhiều phiên. Cập nhật checkbox
> và "Nhật ký cập nhật" mỗi khi hoàn thành một phần — đừng xoá lịch sử, chỉ thêm dòng mới.
> Đọc lại toàn bộ file này trước khi tiếp tục làm dở.

## Vì sao làm việc này (bối cảnh)

Giảng viên yêu cầu: **không được để tiền (balance) trong mock data của `User`**. Lý do sâu xa:
`User` hiện đang gộp chung *định danh* (username, password, email, phone) và *tiền*
(`balance`) trong cùng một bảng — đúng cấu trúc ngân hàng thật không bao giờ làm (xem phân
tích BA trước đó trong hội thoại: CIF vs Account vs Ledger tách 3 lớp).

Quyết định đã chốt với người dùng (2026-09-14):

1. **Tách hẳn thành một service riêng `account-service`** (không chỉ tách bảng trong cùng
   service) — vì "deadline không gấp, muốn code sát thực tế".
2. **Không đổi contract API bên ngoài.** `payment-service`, `tuition-service`, Frontend vẫn
   gọi `auth-service` y như cũ (`/api/auth/users/{id}/debit`, `/credit`, `/login`,
   `/users/{userId}`). `auth-service` trở thành lớp proxy nội bộ gọi sang `account-service`.
   → **Không cần sửa `docs/openapi-auth.json`, không cần báo Frontend-midterm.**
3. **Vẫn giữ seed data cơ bản** (100 triệu / 15 triệu cho 2 user demo) để test với giảng viên
   — không được xoá, chỉ chuyển chỗ lưu.
4. **Vẫn giữ email thật trong seed data** (dùng để test OTP thật qua `notification-service`).
   Việc đổi sang email dạng `@example.com` và "clean" email thật khỏi lịch sử git **CHƯA làm
   ngay** — chờ người dùng chủ động yêu cầu ở một lượt sau. **Không tự ý làm bước này.**

## Ràng buộc bắt buộc (từ CLAUDE.md — không được vi phạm khi làm các phase dưới)

- Không có aggregator pom → mọi lệnh Maven phải là `mvn -f <service>/pom.xml <goal>`.
- `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, maven ở `C:\maven\bin\mvn` (PATH có JDK 8, không dùng được).
- `docker.exe` không nằm trên PATH của Bash tool, dùng PowerShell hoặc prepend path.
- Class test nền tảng phải tự set `api.version=1.41` trong static block cho Testcontainers (xem `AbstractPostgresIT` hiện có ở `auth-service`) — **account-service cũng phải copy y hệt cơ chế này**, không được gỡ.
- Secret chỉ đọc từ env, cấm hardcode. `INTERNAL_API_KEY` dùng lại key hiện có, không tạo key mới.
- Endpoint nội bộ (debit/credit) phải giữ filter API key.
- Tiền dùng `BigDecimal`. Debit/credit phải `@Transactional` + giữ cơ chế khoá chống race (`findByIdForUpdate` kiểu `PESSIMISTIC_WRITE`) — không đơn giản hoá.
- Sửa xong service nào phải chạy `mvn -f <service>/pom.xml test` của service đó và dán output thật, không báo "xong" khi chưa chạy.
- Không tự `docker compose down -v`, không đổi port đang map, không đổi branch, không `git commit/push` khi chưa được yêu cầu rõ ràng. Đang ở nhánh `tuition-service` — **không tự tạo/đổi branch** trừ khi người dùng nói.

## Kiến trúc mục tiêu

```
payment-service ──(HTTP, X-Internal-Api-Key, KHÔNG ĐỔI)──► auth-service
tuition-service ──(HTTP, X-Internal-Api-Key, KHÔNG ĐỔI)──► auth-service
                                                                 │
                                                    AccountServiceClient (MỚI)
                                                    HTTP + X-Internal-Api-Key
                                                                 ▼
                                                         account-service (MỚI, :8085)
                                                         DB riêng: accountdb
```

- `auth-service` (:8081, `authdb`): chỉ còn định danh — username, password, fullName, phone,
  email, JWT, OTP-liên-quan-login-nếu-có. **Không còn cột `balance` trong `User`.**
- `account-service` (:8085, `accountdb`, MỚI): sở hữu `Account` (số dư) và ledger
  (`BalanceEntry`/`EntryType` chuyển nguyên từ auth-service sang). Không lộ ra gateway, chỉ
  nhận gọi service-to-service kèm `X-Internal-Api-Key` — giống hệt cách `payment-service` gọi
  thẳng `http://auth-service:8081` hiện nay (không qua `api-gateway:8080`).
- Response shape ra ngoài (`LoginResponse.balance`, `BalanceResponse.balance`, `GET
  /api/auth/users/{id}` trả `balance`) **giữ nguyên y hệt** — `auth-service` tự ráp dữ liệu từ
  `User` (của chính nó) + kết quả gọi `account-service` trước khi trả về.
- Mỗi `User` mặc định có 1 `Account` (account mặc định), nhưng schema của `Account` không có
  ràng buộc "1 user chỉ 1 account" — `user_id` không unique, có cờ `is_default` — để sau này
  mở thêm account thứ 2 (ví dụ tài khoản tiết kiệm) mà không phải đổi schema.

## Phase 0 — Đã xong (khảo sát nền tảng)

- [x] Đọc `docs/openapi-auth.json` — xác nhận contract cần giữ nguyên (login, debit, credit, get user info đều có `balance`).
- [x] Đọc `docker-compose.yml` — mỗi service 1 DB riêng (`authdb`, `tuitiondb`, `paymentdb`), pattern build/env đã rõ.
- [x] Đọc `init-db.sql` — chỉ có 3 dòng `CREATE DATABASE ... \gexec`, chưa có schema thật (bảng tạo qua Hibernate `ddl-auto: update`).
- [x] Đọc `api-gateway/application.yml` — route hiện tại theo `Path=/api/<service>/**`; xác nhận service-to-service call (`payment` → `auth`) đi thẳng container DNS `http://auth-service:8081`, **không qua gateway** → account-service theo đúng pattern này, không cần thêm route gateway.
- [x] Đọc `User.java`, `BalanceEntry.java`, `BalanceService.java`, `AuthController.java`, `UserRepository.java`, `DemoDataSeeder.java` (auth-service) và `AuthServiceClient.java` (payment-service) — nắm rõ chỗ nào phải chuyển, chỗ nào phải giữ nguyên chữ ký.
- [x] Phát hiện rủi ro liên quan: `DemoDataSeeder.java:33` hard-code email thật của người dùng kèm số dư 100 triệu — đã báo, người dùng xác nhận **giữ nguyên tạm thời** (xem mục 4 ở trên), không tự sửa.

## Phase 1 — Scaffold `account-service`

- [x] Tạo thư mục `account-service/` cùng cấp với các service khác, `pom.xml` riêng (copy cấu trúc từ `auth-service/pom.xml`, bỏ `jjwt-*` và `spring-boot-starter-security` — account-service không có login, chỉ nhận `X-Internal-Api-Key`). Giữ `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `postgresql`, `springdoc-openapi-starter-webmvc-ui`, `lombok`, `spring-boot-starter-test`, `testcontainers` (postgresql + junit-jupiter). (Thêm thêm `spring-boot-starter-actuator` so với kế hoạch gốc — dùng cho healthcheck `/actuator/health`, xem Phase 1 dòng cuối.)
- [x] `AccountApplication.java` (`com.tdtu.ibanking.account`).
- [x] `account-service/src/main/resources/application.yml`: `server.port: 8085`, datasource `jdbc:postgresql://${DB_HOST:localhost}:5432/accountdb`, `jpa.hibernate.ddl-auto: update`, `internal.api-key: ${INTERNAL_API_KEY}`.
- [x] Thêm dòng tạo DB vào `init-db.sql`: `SELECT 'CREATE DATABASE accountdb' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'accountdb')\gexec`.
- [x] Thêm service `account-service` vào `docker-compose.yml` (copy khối `auth-service`, đổi `container_name: ibanking-account`, không cần `ports` map ra host trừ khi muốn debug trực tiếp — các service khác gọi qua network nội bộ Docker). Thêm `depends_on: account-service` vào khối `auth-service` — dùng `condition: service_healthy` (không phải `service_started` như dự kiến ban đầu) vì đã có healthcheck HTTP thật, xem câu hỏi mở #2.

## Phase 2 — Data model & ledger trong `account-service`

- [x] `entity/Account.java`: `id (UUID)`, `userId (UUID, không FK cross-service)`, `accountNumber (String, unique, sinh riêng — KHÔNG suy ra được từ username/CIF)`, `balance (BigDecimal, precision 15 scale 2)`, `currency (String, default "VND")`, `isDefault (boolean)`, `status (enum ACTIVE/LOCKED, mặc định ACTIVE)`, `createdAt`, `updatedAt`.
- [x] Chuyển nguyên `BalanceEntry.java` và `EntryType.java` từ `auth-service` sang `account-service`, đổi `userId` → `accountId`. Giữ nguyên `UniqueConstraint(columnNames = {"transaction_id", "type"})` (cơ chế idempotent theo transactionId đang đúng, không đổi).
- [x] `repository/AccountRepository.java`: `findByUserIdAndIsDefaultTrue`, và bản `@Lock(PESSIMISTIC_WRITE)` kiểu `findByIdForUpdate` y hệt `UserRepository` hiện tại (copy nguyên cơ chế khoá, không "đơn giản hoá"). Thêm thêm `findDefaultByUserIdForUpdate(userId)` (cùng cơ chế khoá) vì debit/credit của account-service nhận `userId` qua path, không phải `accountId`.
- [x] `repository/BalanceEntryRepository.java`: chuyển nguyên từ auth-service, đổi field tham chiếu.
- [x] Chuyển các exception liên quan sang account-service: `InsufficientBalanceException`, `InvalidRefundException`, `TransactionAlreadyFinalizedException`, `UserNotFoundException` → đổi thành `AccountNotFoundException` (đúng domain account-service, có factory method `forUser(userId)`). Map lỗi ở `config/GlobalExceptionHandler.java` riêng của account-service.

## Phase 3 — API nội bộ của `account-service`

- [x] `security/InternalApiKeyFilter.java` — chuyển ý tưởng từ `auth-service/security/InternalApiKeyFilter.java` nhưng viết lại đơn giản hơn vì account-service KHÔNG có Spring Security (bỏ `spring-boot-starter-security` ở Phase 1): filter kiểm tra header trên mọi request, không còn nhánh JWT/ownership. Đăng ký qua `FilterRegistrationBean` trong `config/WebConfig.java` với `urlPatterns=/api/account/*` để không chặn `/actuator/health` (dùng cho healthcheck) và `/swagger-ui`, `/v3/api-docs`. **Toàn bộ endpoint `/api/account/**` bắt buộc key này, không có JWT.**
- [x] `controller/AccountController.java`, prefix `/api/account` (giữ đúng convention `/api/<service>/**` của repo dù không lộ qua gateway):
  - `POST /api/account/users/{userId}/accounts` — tạo account mặc định cho 1 user (idempotent: nếu đã có account mặc định thì trả về account đó, không tạo trùng).
  - `GET /api/account/users/{userId}/balance` — trả `AccountBalanceResponse { accountId, userId, balance }`.
  - `POST /api/account/users/{userId}/debit` — body `{ amount, transactionId }`, trừ trên account mặc định của user, cùng logic idempotent-theo-transactionId như `BalanceService.debit` hiện tại.
  - `POST /api/account/users/{userId}/credit` — tương tự, logic như `BalanceService.credit` hiện tại.
- [x] `service/AccountBalanceService.java` — chuyển nguyên logic từ `auth-service/service/BalanceService.java` (đổi `User` → `Account`).

## Phase 4 — Refactor `auth-service` thành lớp proxy

- [x] Xoá field `balance` khỏi `entity/User.java`.
  - **Lưu ý:** `ddl-auto: update` sẽ KHÔNG tự xoá cột `balance` cũ khỏi bảng `users` (Hibernate chỉ thêm, không xoá). Cột rác sẽ còn treo lại trong DB demo hiện có. Ghi rõ trong PR/báo cáo; nếu muốn dọn sạch thật sự cần 1 câu `ALTER TABLE users DROP COLUMN balance;` chạy tay (không tự động chạy — hỏi người dùng trước vì đây là thao tác đổi schema trên DB demo có dữ liệu). **CHƯA chạy** — để nguyên theo đúng ràng buộc.
- [x] Xoá `entity/BalanceEntry.java`, `entity/EntryType.java`, `repository/BalanceEntryRepository.java`, và 3 exception (`InsufficientBalanceException`, `InvalidRefundException`, `TransactionAlreadyFinalizedException`) khỏi `auth-service` (đã chuyển sang account-service ở Phase 2). Đã xoá thêm `UserRepository.findByIdForUpdate` (khoá pessimistic) vì không còn nơi nào gọi — khoá tương đương giờ nằm ở `AccountRepository` của account-service.
- [x] Tạo `client/AccountServiceClient.java` trong auth-service — copy pattern từ `payment-service/client/AuthServiceClient.java` (RestTemplate + header `X-Internal-Api-Key`, base URL đọc qua config `account-service.base-url`, KHÔNG hardcode). Method: `getBalance(userId)`, `debit(userId, amount, transactionId)`, `credit(userId, amount, transactionId)`, `ensureAccount(userId, initialBalance)` (có retry 3 lần / 500ms riêng cho `ensureAccount`, các method còn lại KHÔNG retry vì là request thời gian thực). Thêm `config/RestTemplateConfig.java` (auth-service trước đây chưa có RestTemplate bean) và `dto/CreateAccountRequest.java` (mirror account-service) — tái dùng nguyên `dto/BalanceChangeRequest.java` và `dto/BalanceResponse.java` hiện có làm body/response cho debit/credit/getBalance (không tạo DTO trùng lặp vì shape giống hệt).
- [x] Viết lại `service/BalanceService.java` thành lớp mỏng: gọi `AccountServiceClient`, map response sang `dto/BalanceResponse.java` hiện có của auth-service (giữ nguyên field `userId`/`balance`). 404 từ account-service được bắt (`HttpClientErrorException.NotFound`) và ném lại `UserNotFoundException` (giữ nguyên class cũ) để giữ đúng format lỗi cũ.
- [x] `controller/AuthController.java`:
  - `debit`/`credit`: gọi `balanceService` như cũ, không đổi chữ ký endpoint/response. Đã BỎ khối `try/catch DataIntegrityViolationException` cũ (dead code sau refactor — auth-service không còn ghi DB local cho balance nữa nên exception đó không bao giờ được ném từ đây; race/unique-constraint idempotent giờ xử lý bên account-service).
  - `authenticateUser` (login) và `getUserInfo`: đổi từ đọc `user.getBalance()` trực tiếp sang gọi `balanceService.getBalance(userId).getBalance()`, ráp vào `LoginResponse`/response map giữ nguyên shape cũ.
- [x] `config/DemoDataSeeder.java`: bỏ tham số `balance` khỏi việc set trực tiếp lên `User`; sau khi `userRepository.save(user)`, gọi `accountServiceClient.ensureAccount(user.getId(), balance)` với đúng số tiền demo cũ (100 triệu / 15 triệu — **giữ nguyên số**, chỉ đổi chỗ lưu). Dùng chung `POST /api/account/users/{userId}/accounts` với `initialBalance` optional (đúng endpoint account-service đã có sẵn từ Phase 3, không cần thêm endpoint mới). Bọc lời gọi `ensureAccount` trong try/catch, log warning thay vì để lỗi lan ra làm sập `CommandLineRunner`/context — quyết định này để chịu được account-service tạm thời không sẵn sàng (và bắt buộc phải có để test `AuthControllerIT` chạy được, vì trong môi trường test không có account-service thật — xem Phase 5 bên dưới).

## Phase 5 — Test

- [x] Tạo `account-service/src/test/java/.../support/AbstractPostgresIT.java` — copy nguyên bản từ `auth-service` (bao gồm static block `api.version=1.41`, **không được gỡ**). Đổi `withDatabaseName("authdb")` → `accountdb`, và helper `createUser(balance)` → `createAccount(balance)` (userId/accountNumber sinh ngẫu nhiên).
- [x] Chuyển `BalanceServiceTest.java` và `BalanceConcurrencyIT.java` sang `account-service`, đổi tên/tham chiếu `User`→`Account`, `userId`→`accountId` cho phù hợp. Test race-condition (khoá pessimistic khi 2 request debit cùng lúc) phải giữ nguyên ý nghĩa.
- [x] `auth-service` cần test mới cho phần proxy: `AuthControllerIT` hiện test debit/credit/login end-to-end trong DB auth-service — sau refactor, các endpoint này gọi HTTP ra ngoài tới `account-service`. Đã chọn **(b) MockRestServiceServer** bọc RestTemplate thật (bean `restTemplate` dùng chung cho `AccountServiceClient`) — verify được luôn tầng serialize/deserialize JSON, đảm bảo response ra ngoài (`LoginResponse`, `BalanceResponse`, body `GET /users/{id}`) giữ đúng shape cũ. Đã xoá `BalanceServiceTest.java` và `BalanceConcurrencyIT.java` cũ của auth-service (logic đó đã chuyển hẳn sang account-service ở Phase 5 trước, bản cũ ở auth-service giờ tham chiếu các class đã xoá nên không còn biên dịch được). `AbstractPostgresIT.createUser()` bỏ tham số `balance` (không còn ý nghĩa), thêm property test `account-service.base-url=http://account-service-test:8085` (hostname giả, không bao giờ được gọi HTTP thật — mọi lời gọi trong test đều đi qua MockRestServiceServer).
  - **Vấn đề phát sinh khi code**: `DemoDataSeeder` cũng gọi `AccountServiceClient` lúc `CommandLineRunner` chạy — nhưng đó là lúc Spring context khởi động, TRƯỚC khi bất kỳ `@BeforeEach` nào của test kịp bind `MockRestServiceServer` vào `RestTemplate`. Vì account-service không tồn tại trong môi trường test (chỉ có Postgres Testcontainer), lời gọi `ensureAccount` lúc seed sẽ luôn thật sự lỗi (DNS không phân giải được `account-service-test`). Ban đầu `DemoDataSeeder` để lỗi lan ra ngoài `@Transactional run()` sẽ làm sập toàn bộ Spring context (context cache dùng chung cho mọi test class) → **giải pháp**: bọc `ensureAccount` trong try/catch, chỉ log warning (xem Phase 4). Đây cũng là quyết định hợp lý cho production: seeder không nên làm sập cả service chỉ vì account-service tạm thời chưa sẵn sàng.
  - Thêm các test happy-path mới (ngoài 4 test cũ) để verify shape: `getUserInfoWithInternalKeyReturnsSameShape`, `debitWithInternalApiKeyProxiesToAccountService`, `creditWithInternalApiKeyProxiesToAccountService`, và 2 test lỗi `debitWhenAccountServiceReturnsConflictPropagatesStatusAndMessage` (409 từ account-service giữ nguyên status + message), `debitWhenAccountServiceReturnsNotFoundMapsToUserNotFound` (404 giữ nguyên status). Tổng `AuthControllerIT`: 9 test.
- [x] Chạy `mvn -f account-service/pom.xml test` — dán output thật, sửa tới khi pass. Kết quả: **BUILD SUCCESS, 6/6 test pass** (xem Nhật ký cập nhật bên dưới).
- [x] Chạy `mvn -f auth-service/pom.xml test` — dán output thật, sửa tới khi pass. Kết quả: **BUILD SUCCESS, 9/9 test pass** (xem Nhật ký cập nhật 2026-09-14 bên dưới).
- [x] Chạy `mvn -f payment-service/pom.xml test` và `mvn -f tuition-service/pom.xml test` để xác nhận KHÔNG có gì vỡ ở phía gọi (2 service này không đổi code nhưng phụ thuộc hành vi `auth-service` không đổi). Kết quả: **payment-service BUILD SUCCESS 7/7**, **tuition-service BUILD SUCCESS 11/11** — cả hai không sửa code, chỉ chạy để có bằng chứng.

## Phase 6 — Kiểm chứng cuối

- [x] Chạy `docker compose up -d --build` (không kèm `-v`, không xoá volume) — kiểm tra `account-service` start OK, `auth-service` seed thành công (log "Demo users seeded..."), gọi thử `POST /api/auth/login` qua gateway `:8080` xem `balance` trong response có đúng số cũ (100 triệu / 15 triệu) không.
- [x] Gọi thử `/api/auth/users/{id}/debit` qua đường cũ (giả lập như payment-service gọi) — xác nhận vẫn 200 kèm `BalanceResponse` đúng shape, xác nhận idempotent theo `transactionId` vẫn hoạt động (gọi lại cùng `transactionId` không bị trừ 2 lần).
- [x] Rà lại `docs/openapi-auth.json` — xác nhận không cần sửa gì (contract không đổi). Nếu phát hiện có chỗ cần sửa (ví dụ do lúc code phát sinh field mới), phải cập nhật file này VÀ báo để sửa `Frontend-midterm/docs/API-FRONTEND.md` theo mục 6 CLAUDE.md.
- [x] Review lại toàn bộ diff, đảm bảo không commit `target/`, `BOOT-INF/`, `.env`.

## Việc CHƯA làm — chờ lệnh người dùng

- [ ] **Không tự làm:** đổi email thật trong `DemoDataSeeder.java` sang `@example.com`.
- [ ] **Không tự làm:** "clean" email thật khỏi lịch sử git (`git filter-repo`/`BFG` hay tương tự — đây là thao tác phá lịch sử, ảnh hưởng toàn bộ remote, tuyệt đối cần hỏi + xác nhận phạm vi trước khi chạy, kể cả khi được yêu cầu).
- [ ] Chỉ thực hiện 2 việc trên khi người dùng chủ động nhắc lại, đúng như đã hẹn ở lượt trao đổi 2026-09-14.

## Câu hỏi còn mở (hỏi lại nếu chưa rõ khi bắt đầu code)

1. Endpoint seeding cho account-service (Phase 4) — dùng `POST /api/account/users/{userId}/accounts` với `initialBalance` optional, hay tách riêng 1 endpoint chỉ-để-seed? Ảnh hưởng tới việc endpoint đó có nên tồn tại ở production hay chỉ nên gated sau 1 flag/profile `demo`.
2. Có cần healthcheck HTTP thật cho `account-service` trong `docker-compose.yml` (thay vì chỉ `service_started`) để tránh race lúc `auth-service` seed trước khi account-service sẵn sàng?
3. Cột `balance` rác còn lại trong bảng `users` sau khi xoá field khỏi entity — có muốn DROP COLUMN thủ công luôn, hay để đó (chấp nhận cột chết) vì đây là DB demo?

## Nhật ký cập nhật

- **2026-09-14** — Tạo file, hoàn thành Phase 0 (khảo sát toàn bộ file liên quan: openapi-auth.json, docker-compose.yml, init-db.sql, api-gateway/application.yml, User/BalanceEntry/BalanceService/AuthController/UserRepository/DemoDataSeeder của auth-service, AuthServiceClient của payment-service). Quyết định kiến trúc: account-service port 8085, DB `accountdb`, không lộ qua gateway, contract bên ngoài giữ nguyên 100%.

- **2026-09-14** — Hoàn thành Phase 1, Phase 2, Phase 3 và phần Phase 5 riêng của account-service . Đã tạo mới toàn bộ `account-service/` (pom.xml, `AccountApplication.java`, `application.yml`, `Dockerfile`, entity `Account`/`AccountStatus`/`BalanceEntry`/`EntryType`, `repository/AccountRepository.java` + `BalanceEntryRepository.java`, exception `AccountNotFoundException`/`InsufficientBalanceException`/`InvalidRefundException`/`TransactionAlreadyFinalizedException`, `config/GlobalExceptionHandler.java`, `config/WebConfig.java`, `security/InternalApiKeyFilter.java`, `service/AccountBalanceService.java`, `controller/AccountController.java`, dto `CreateAccountRequest`/`AccountResponse`/`BalanceChangeRequest`/`AccountBalanceResponse`, test `support/AbstractPostgresIT.java`, `service/BalanceServiceTest.java`, `service/BalanceConcurrencyIT.java`). Đã sửa `init-db.sql` (thêm dòng `CREATE DATABASE accountdb`) và `docker-compose.yml` (thêm khối `account-service`, thêm `depends_on.account-service` vào `auth-service`).

  Quyết định kỹ thuật cụ thể:
  - **Healthcheck**: chọn thêm `spring-boot-starter-actuator`, chỉ expose đúng endpoint `health` (`management.endpoints.web.exposure.include: health`, `show-details: never`) — không cài thêm curl vào image `eclipse-temurin:17-jdk-alpine` vì alpine đã có sẵn `wget` (busybox). `docker-compose.yml` dùng `test: ["CMD-SHELL", "wget --no-verbose --tries=1 --spider http://localhost:8085/actuator/health || exit 1"]`, `interval: 10s`, `timeout: 5s`, `retries: 5`, theo đúng cú pháp `healthcheck:` của khối `postgres` hiện có. `auth-service` đổi `depends_on.account-service` thành `condition: service_healthy` (tốt hơn `service_started` vì đã có healthcheck HTTP thật — trả lời luôn câu hỏi mở #2 trong file này).
  - **InternalApiKeyFilter**: account-service KHÔNG có `spring-boot-starter-security` (chủ động bỏ theo Phase 1) nên KHÔNG thể copy y hệt bản của auth-service (bản gốc tích hợp `SecurityContextHolder`/`SecurityFilterChain`). Viết lại thành `OncePerRequestFilter` thuần, kiểm tra header `X-Internal-Api-Key` trên MỌI request nó chặn, không còn nhánh "internal-or-owner" (không cần vì account-service không có JWT/end-user). Đăng ký filter qua `FilterRegistrationBean` trong `config/WebConfig.java` với `addUrlPatterns("/api/account/*")` để KHÔNG chặn `/actuator/health`, `/swagger-ui/**`, `/v3/api-docs/**`.
  - **Endpoint seeding** (trả lời câu hỏi mở #1): dùng chung `POST /api/account/users/{userId}/accounts` với `initialBalance` optional trong body, KHÔNG tách endpoint riêng — đúng theo chỉ định của lượt giao việc này. Endpoint idempotent tuyệt đối: nếu đã có account mặc định thì trả về nguyên trạng, `initialBalance` gửi kèm bị bỏ qua hoàn toàn (không có nhánh "update balance nếu khác").
  - **Sinh accountNumber**: 12 chữ số ngẫu nhiên (`SecureRandom`), lặp lại tới khi không trùng (`existsByAccountNumber`) — không suy ra được từ `userId`/username.
  - **Khoá pessimistic cho debit/credit**: vì 2 endpoint này nhận `userId` (không phải `accountId`) qua path, thêm method mới `findDefaultByUserIdForUpdate(userId)` trong `AccountRepository` (cùng cơ chế `@Lock(PESSIMISTIC_WRITE)` như `findByIdForUpdate` gốc) thay vì chỉ có bản theo `id` như `UserRepository` gốc.
  - **AuthController.debit/credit trả 200 luôn** cho endpoint tạo account (không phân biệt 200 tạo-mới vs đã-tồn-tại) vì hành vi idempotent khiến việc phân biệt mã trạng thái không có ý nghĩa với caller (auth-service ở Phase 4 chỉ cần map response, không cần biết mới hay cũ).
  - `AbstractPostgresIT` của account-service giữ **nguyên xi** static block `api.version=1.41` như yêu cầu, chỉ đổi tên DB Testcontainers thành `accountdb` và helper tạo dữ liệu test (`createAccount` thay cho `createUser`).

  Output thật của `mvn -f account-service/pom.xml test` (đã set `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, dùng `C:\maven\bin\mvn`):

  ```
  [INFO] Running com.tdtu.ibanking.account.service.BalanceConcurrencyIT
  ...
  [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.278 s -- in com.tdtu.ibanking.account.service.BalanceConcurrencyIT
  [INFO] Running com.tdtu.ibanking.account.service.BalanceServiceTest
  [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.108 s -- in com.tdtu.ibanking.account.service.BalanceServiceTest
  [INFO]
  [INFO] Results:
  [INFO]
  [INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
  [INFO]
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  10.597 s
  [INFO] Finished at: 2026-09-14T13:34:55+07:00
  [INFO] ------------------------------------------------------------------------
  ```

  **Chưa làm / để lại cho Phase 4**: mọi thứ liên quan tới refactor `auth-service` thành proxy (xoá `balance` khỏi `User`, tạo `AccountServiceClient`, viết lại `BalanceService`/`AuthController`/`DemoDataSeeder` của auth-service, test `AuthControllerIT` mock/`MockRestServiceServer`, chạy `mvn -f auth-service|payment-service|tuition-service/pom.xml test`, `docker compose up -d --build` kiểm chứng cuối) — đúng phạm vi được giao, KHÔNG động vào.

- **2026-09-14** — Hoàn thành Phase 4 và phần Phase 5 còn lại liên quan tới `auth-service`/`payment-service`/`tuition-service` (đợt làm riêng cho phần này).

  **File đã sửa trong `auth-service`:**
  - `entity/User.java` — xoá field `balance` (và import `BigDecimal` không dùng nữa). Cột `balance` cũ trong bảng `users` KHÔNG bị xoá (Hibernate `ddl-auto: update` chỉ thêm cột, không xoá) — để nguyên như đã ghi rõ trong Phase 4, KHÔNG tự chạy `ALTER TABLE ... DROP COLUMN` (trả lời câu hỏi mở #3: giữ nguyên cột chết, chờ người dùng quyết định).
  - `repository/UserRepository.java` — xoá `findByIdForUpdate` (khoá pessimistic không còn ai gọi từ Phase 4).
  - `service/BalanceService.java` — viết lại hoàn toàn thành lớp mỏng gọi `AccountServiceClient`.
  - `controller/AuthController.java` — `authenticateUser`/`getUserInfo` gọi `balanceService.getBalance(userId)` thay vì `user.getBalance()`; bỏ `try/catch DataIntegrityViolationException` (dead code) ở `debit`/`credit`.
  - `config/DemoDataSeeder.java` — gọi `accountServiceClient.ensureAccount(user.getId(), balance)` sau `userRepository.save`, bọc try/catch + log warning.
  - `config/GlobalExceptionHandler.java` — bỏ 3 handler (`InsufficientBalanceException`/`InvalidRefundException`/`TransactionAlreadyFinalizedException`), thêm handler chung `HttpStatusCodeException` (parse `{"message": ...}` từ response body của account-service, giữ nguyên status code).
  - `src/main/resources/application.yml` — thêm `account-service.base-url: ${ACCOUNT_SERVICE_BASE_URL:http://account-service:8085}`.
  - `src/test/java/.../support/AbstractPostgresIT.java` — `createUser()` bỏ tham số `balance`; thêm `account-service.base-url` test property; sửa Javadoc lớp cho đúng bối cảnh mới.
  - `src/test/java/.../controller/AuthControllerIT.java` — viết lại, dùng `MockRestServiceServer`, thêm 5 test mới (tổng 9 test).

  **File tạo mới trong `auth-service`:**
  - `client/AccountServiceClient.java`, `config/RestTemplateConfig.java`, `dto/CreateAccountRequest.java`.

  **File xoá khỏi `auth-service`:**
  - `entity/BalanceEntry.java`, `entity/EntryType.java`, `repository/BalanceEntryRepository.java`.
  - `exception/InsufficientBalanceException.java`, `exception/InvalidRefundException.java`, `exception/TransactionAlreadyFinalizedException.java` (giữ lại `UserNotFoundException.java` — vẫn dùng để map 404 từ account-service).
  - `src/test/java/.../service/BalanceServiceTest.java`, `src/test/java/.../service/BalanceConcurrencyIT.java` (logic này đã có bản tương đương ở `account-service`, bản cũ ở auth-service không còn biên dịch được sau khi xoá `BalanceService` cũ).

  **Quyết định kỹ thuật cụ thể:**
  - Tái dùng nguyên `dto/BalanceChangeRequest.java` và `dto/BalanceResponse.java` hiện có của auth-service làm request/response body khi gọi account-service (shape giống hệt `{amount,transactionId}` / `{userId,balance}`) — không tạo DTO trùng lặp, giảm số file mới.
  - 404 từ account-service (`GET/POST .../{userId}/...` khi chưa có account) được `BalanceService` bắt (`HttpClientErrorException.NotFound`) và ném lại `UserNotFoundException` để giữ đúng message/format cũ (`GlobalExceptionHandler.handleUserNotFound`, HTTP 404).
  - 409 (số dư không đủ / giao dịch đã chốt / hoàn tiền không hợp lệ) và các lỗi HTTP khác từ account-service KHÔNG có exception riêng ở auth-service nữa — `GlobalExceptionHandler.handleAccountServiceError(HttpStatusCodeException)` forward nguyên status code + parse field `message` từ body JSON của account-service, giữ đúng format `{"message": ...}` mà Frontend/payment-service/tuition-service đang parse.
  - `AuthController.debit`/`credit` bỏ khối `try/catch (DataIntegrityViolationException)` cũ: exception đó chỉ có thể ném ra khi ghi trực tiếp xuống bảng `balance_entries` cục bộ (ràng buộc UNIQUE(transaction_id,type)), nhưng từ Phase 4 auth-service không còn ghi bảng đó nữa (đã chuyển hẳn sang account-service) nên khối catch này là dead code — xoá theo đúng tinh thần CLAUDE.md mục 2 (không rải try/catch lỗi trong controller khi không cần).
  - `AuthControllerIT` dùng **MockRestServiceServer** (phương án b được khuyến nghị trong TODO) bọc chính bean `RestTemplate` mà `AccountServiceClient` dùng — verify được luôn tầng serialize/deserialize JSON thật, không chỉ mock lời gọi Java. Thêm 2 test lỗi (409/404 từ account-service) để chứng minh `GlobalExceptionHandler` forward đúng status+message.
  - **Vấn đề quan trọng phát hiện khi code**: `DemoDataSeeder` (chạy trong `CommandLineRunner` lúc Spring context khởi động, TRƯỚC mọi `@BeforeEach` của test) cũng gọi `AccountServiceClient.ensureAccount` qua cùng bean `RestTemplate` — nhưng account-service không tồn tại trong môi trường test (chỉ có Postgres Testcontainer). Ban đầu để lỗi lan ra sẽ làm sập toàn bộ Spring context (context được cache dùng chung cho cả module) → mọi test đều fail dù không liên quan. Đã sửa `DemoDataSeeder.upsert(...)` bọc `ensureAccount` trong try/catch, chỉ log warning (không log balance, chỉ log username + message lỗi — tuân thủ CLAUDE.md mục 3 "không log ... số dư kèm định danh"). Quyết định này cũng hợp lý cho production: seeder không nên crash cả service chỉ vì account-service tạm thời chưa healthy dù đã có `depends_on.condition: service_healthy`.

  **Output thật của `mvn -f auth-service/pom.xml test`** (set `JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8`, chạy qua PowerShell vì Bash tool xử lý sai đường dẫn có khoảng trắng `C:\VS code\...` — không phải lỗi biên dịch, chỉ là công cụ liệt kê file bị lỗi):

  ```
  [INFO] Running com.tdtu.ibanking.auth.controller.AuthControllerIT
  ...
  [WARN] ... DemoDataSeeder : Không thể ensureAccount cho user 524h0088 trên account-service: I/O error on POST request for "http://account-service-test:8085/api/account/users/.../accounts": account-service-test
  [WARN] ... DemoDataSeeder : Không thể ensureAccount cho user 524h0456 trên account-service: I/O error on POST request for "http://account-service-test:8085/api/account/users/.../accounts": account-service-test
  [INFO] ... DemoDataSeeder : Demo users seeded (524h0088, 524h0456)
  [INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.62 s -- in com.tdtu.ibanking.auth.controller.AuthControllerIT
  [INFO]
  [INFO] Results:
  [INFO]
  [INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
  [INFO]
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  16.545 s
  [INFO] Finished at: 2026-09-14T13:47:43+07:00
  [INFO] ------------------------------------------------------------------------
  ```

  (2 dòng WARN ở trên là kỳ vọng — xem giải thích ở trên, account-service không chạy trong môi trường test nên `ensureAccount` lúc seed lỗi có chủ đích, không ảnh hưởng tới các test vì mọi lời gọi HTTP trong từng test case đều đi qua `MockRestServiceServer` riêng.)

  **Output thật của `mvn -f payment-service/pom.xml test`** (không sửa code payment-service, chạy để xác nhận không vỡ):

  ```
  [INFO] Running com.tdtu.ibanking.payment.service.PaymentServiceTest
  [INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.460 s -- in com.tdtu.ibanking.payment.service.PaymentServiceTest
  [INFO] Running com.tdtu.ibanking.payment.service.RateLimitTest
  [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.101 s -- in com.tdtu.ibanking.payment.service.RateLimitTest
  [INFO]
  [INFO] Results:
  [INFO]
  [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
  [INFO]
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  13.214 s
  [INFO] Finished at: 2026-09-14T13:48:04+07:00
  [INFO] ------------------------------------------------------------------------
  ```

  **Output thật của `mvn -f tuition-service/pom.xml test`** (không sửa code tuition-service, chạy để xác nhận không vỡ):

  ```
  [INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.427 s -- in com.tdtu.ibanking.tuition.controller.TuitionControllerIT
  [INFO] Running com.tdtu.ibanking.tuition.service.TuitionMarkPaidConcurrencyIT
  [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.747 s -- in com.tdtu.ibanking.tuition.service.TuitionMarkPaidConcurrencyIT
  [INFO] Running com.tdtu.ibanking.tuition.service.TuitionServiceTest
  [INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.107 s -- in com.tdtu.ibanking.tuition.service.TuitionServiceTest
  [INFO]
  [INFO] Results:
  [INFO]
  [INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
  [INFO]
  [INFO] ------------------------------------------------------------------------
  [INFO] BUILD SUCCESS
  [INFO] ------------------------------------------------------------------------
  [INFO] Total time:  11.267 s
  [INFO] Finished at: 2026-09-14T13:48:22+07:00
  [INFO] ------------------------------------------------------------------------
  ```

  **Sai lệch so với kế hoạch ban đầu:**
  - Giữ lại `exception/UserNotFoundException.java` (kế hoạch gốc trong lời giao việc liệt kê "3 exception" cần xoá, không có exception này trong danh sách — đã đối chiếu kỹ, đúng ý định gốc) vì vẫn cần để map 404 với đúng format cũ.
  - Không tạo thêm DTO `AccountResponse`/`AccountBalanceResponse` riêng cho auth-service như gợi ý ban đầu — tái dùng `BalanceChangeRequest`/`BalanceResponse` sẵn có vì shape giống hệt, giảm trùng lặp code.
  - Không sửa `docker-compose.yml`/`init-db.sql` (không cần thiết — Phase 1-3 đã cấu hình đủ, biến `ACCOUNT_SERVICE_BASE_URL` không set trong compose vẫn dùng default `http://account-service:8085` khớp đúng container DNS).
  - **CHƯA làm Phase 6** (kiểm chứng cuối bằng `docker compose up -d --build` + gọi thử API qua gateway thật) — nằm ngoài phạm vi được giao cho lượt này (chỉ giao Phase 4 + phần Phase 5 liên quan auth/payment/tuition). Cần một lượt riêng để chạy Phase 6 nếu người dùng muốn xác nhận end-to-end qua Docker thật.

- **2026-09-14** — Hoàn thành Phase 6 (kiểm chứng cuối bằng Docker thật) + rà soát docs (đợt làm riêng cho phần này). **Toàn bộ TODO này giờ đã xong hết các phase chính (0-6)**, chỉ còn 2 việc ở mục "Việc CHƯA làm — chờ lệnh người dùng" (đổi email demo, clean git history) là cố ý để lại theo đúng thoả thuận.

  ### A. Docker thật — phát hiện và sửa 1 bug thật (silent-fail 403)

  Build jar (`mvn -f account-service/pom.xml package -DskipTests`, `mvn -f auth-service/pom.xml package -DskipTests` — 2 service duy nhất chưa có jar sẵn trong `target/`) rồi `docker compose up -d --build` (không kèm `-v`). Toàn bộ 9 container start thành công, `postgres` và `ibanking-account` chuyển `healthy` trước khi `ibanking-auth` start (đúng như `depends_on.condition: service_healthy` đã cấu hình ở Phase 1).

  **Bug phát hiện**: log `auth-service` sau lần start đầu tiên cho thấy:
  ```
  WARN ... DemoDataSeeder : Không thể ensureAccount cho user 524h0088 trên account-service: 403 : "{"message":"Endpoint nội bộ, không được gọi trực tiếp"}"
  WARN ... DemoDataSeeder : Không thể ensureAccount cho user 524h0456 trên account-service: 403 : "{"message":"Endpoint nội bộ, không được gọi trực tiếp"}"
  INFO ... DemoDataSeeder : Demo users seeded (524h0088, 524h0456)
  ```
  Đây chính xác là kiểu "seed bị silent-fail" mà lượt giao việc cảnh báo trước — `DemoDataSeeder` log warning rồi tiếp tục, log cuối vẫn ghi "seeded" nên rất dễ bị bỏ qua nếu không đọc kỹ log.

  **Nguyên nhân gốc** (không phải do `AccountServiceClient`/`DemoDataSeeder` — cả hai gửi đúng `X-Internal-Api-Key`): bug nằm ở `account-service/src/main/java/com/tdtu/ibanking/account/config/WebConfig.java`. Bản gốc:
  ```java
  @Bean
  public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilterRegistration() {
      FilterRegistrationBean<InternalApiKeyFilter> registration =
              new FilterRegistrationBean<>(new InternalApiKeyFilter());
      ...
  }
  ```
  `new InternalApiKeyFilter()` được tạo **thủ công bên trong thân method `@Bean`** — Spring chỉ áp dụng bean post-processing (bao gồm xử lý `@Value`) lên đúng object mà method `@Bean` trả về trực tiếp (ở đây là `FilterRegistrationBean`), KHÔNG "chui vào" xử lý object `InternalApiKeyFilter` được `new` lồng bên trong. Kết quả: field `internalApiKey` (được đánh dấu `@Value("${internal.api-key}")`) không bao giờ được gán, giữ giá trị mặc định `null`. Trong `doFilterInternal`, `constantTimeEquals(providedKey, null)` luôn trả `false` bất kể key gửi lên đúng hay sai → **mọi request tới `/api/account/**` đều bị từ chối 403**, kể cả debit/credit/getBalance thật (không chỉ lúc seed).

  So sánh với `auth-service/config/SecurityConfig.java` (đúng): filter được khai `@Bean` RIÊNG (`internalApiKeyFilter()` trả thẳng `new InternalApiKeyFilter()`), nên Spring post-process đúng object đó, `@Value` được gán bình thường. `account-service` không copy đúng pattern này ở Phase 3.

  **Đã sửa** `account-service/src/main/java/com/tdtu/ibanking/account/config/WebConfig.java`: tách `InternalApiKeyFilter` thành `@Bean` riêng, rồi mới inject vào `FilterRegistrationBean`:
  ```java
  @Bean
  public InternalApiKeyFilter internalApiKeyFilter() {
      return new InternalApiKeyFilter();
  }

  @Bean
  public FilterRegistrationBean<InternalApiKeyFilter> internalApiKeyFilterRegistration(
          InternalApiKeyFilter internalApiKeyFilter) {
      FilterRegistrationBean<InternalApiKeyFilter> registration =
              new FilterRegistrationBean<>(internalApiKeyFilter);
      registration.addUrlPatterns("/api/account/*");
      registration.setOrder(1);
      return registration;
  }
  ```
  Đã chạy lại `mvn -f account-service/pom.xml test` sau khi sửa: **BUILD SUCCESS, 6/6 test pass** (không vỡ gì — test hiện có không đi qua `WebConfig`/servlet filter thật nên không phát hiện được bug này, đúng như kỳ vọng vì đây là bug về wiring Spring context, không phải logic nghiệp vụ). Đã build lại jar, `docker compose up -d --build account-service` rồi `docker compose restart auth-service` để seed lại — log sau khi sửa:
  ```
  INFO ... AuthApplication : Started AuthApplication in 3.239 seconds (process running for 3.554)
  INFO ... DemoDataSeeder : Demo users seeded (524h0088, 524h0456)
  ```
  Không còn WARN nào — `ensureAccount` gọi thành công qua network Docker thật ngay từ lần đầu.

  ### B. Gọi thử API qua gateway/network thật

  - `POST /api/auth/login` qua gateway `:8080` cho cả 2 user demo: `524h0088` → `balance: 100000000.00`, `524h0456` → `balance: 15000000.00` — đúng số cũ, đúng shape `{accessToken, userId, email, balance}` khớp `docs/openapi-auth.json`.
  - Gọi `debit` **không qua gateway** (gateway chặn 401 vì gateway bắt buộc JWT cho mọi path trừ `/login` — đây là hành vi đúng của gateway, không phải bug; muốn test debit/credit như `payment-service` thật sự gọi phải gọi thẳng container DNS `http://auth-service:8081`, không qua `:8080`). Đã `docker exec` vào container `ibanking-payment` (đã có sẵn `INTERNAL_API_KEY` trong env, dùng `wget` busybox, không in giá trị key ra log) để gọi đúng như `payment-service` gọi thật:
    - Debit lần 1 (`transactionId` mới): `200 {"userId":"...a2bf...","balance":99000000.00}` — trừ đúng 1.000.000 từ 100.000.000.
    - Debit lần 2 (CÙNG `transactionId`): `200 {"userId":"...a2bf...","balance":99000000.00}` — **idempotent đúng**, không bị trừ lần 2. Xác nhận cơ chế `UNIQUE(transaction_id, type)` trên `balance_entries` (nay ở `account-service`) vẫn hoạt động đúng qua network HTTP thật giữa 2 service, không chỉ qua mock/test.
    - Credit hoàn lại bằng `transactionId` MỚI (không phải `transactionId` debit gốc) → `409` — đúng hành vi "từ chối nếu chưa từng debit" (README mục 5.1). Credit lại bằng ĐÚNG `transactionId` debit gốc → `200 {"balance":100000000.00}` — hoàn tiền đúng, khôi phục lại số dư demo về nguyên trạng 100 triệu (vì lệnh debit test ở trên là giao dịch thật, có ảnh hưởng tới dữ liệu demo nên phải hoàn lại, không để lại tác dụng phụ cho giảng viên).
  - Xác nhận lại lần cuối bằng `GET .../login` — `524h0088` trả đúng `balance: 100000000.00`.

  ### C. Rà soát docs

  - `docs/openapi-auth.json`: đối chiếu từng schema (`LoginResponse.accessToken/userId/email/balance`, `BalanceResponse.userId/balance`, `BalanceChangeRequest.amount/transactionId`, mô tả 403/404/409 của debit/credit) với `LoginResponse.java`/`BalanceResponse.java`/`AuthController.java` thực tế và với kết quả gọi thử thật ở mục B — **khớp hoàn toàn, không cần sửa gì**. Đúng như Phase 0 và điểm 2 trong "Quyết định đã chốt" đã dự đoán: contract bên ngoài không đổi nên không cần báo `Frontend-midterm`.
  - `README.md` (gốc): có mục "1. Kiến trúc" (sơ đồ ASCII + bảng "Vai trò từng service") và mục "8. Cấu trúc thư mục" liệt kê rõ từng service kèm port/DB — theo đúng tiêu chí ở lượt giao việc, đã **cập nhật** các mục sau để phản ánh đúng thực tế mới (không đụng vào các mục nghiệp vụ khác như luồng OTP/saga vì không liên quan tới việc tách account-service):
    - Mục 1: thêm `account-service :8085` vào sơ đồ kiến trúc + bảng "Vai trò từng service" (ghi rõ "nội bộ, không publish ra host", "không có route nào trong api-gateway"), sửa mô tả `auth-service` (bỏ "ghi sổ cái balance_entries", thêm "số dư được proxy qua account-service").
    - Mục 3 (Xác thực & phân quyền): thêm `account-service` vào danh sách nơi có `InternalApiKeyFilter`, ghi chú luồng `auth-service` → `account-service`.
    - Mục 6.2/6.4: thêm bước build jar `account-service`, sửa "3 database" → "4 database" (`accountdb`), ghi chú `depends_on.condition: service_healthy`.
    - Mục 7.2/7.5: sửa số lượng test (`auth-service` 10→9, thêm dòng `account-service` 6 test), sửa bảng chi tiết test (`BalanceServiceTest`/`BalanceConcurrencyIT` nay thuộc `account-service`), sửa tham chiếu `UserRepository.findByIdForUpdate` → `AccountRepository.findDefaultByUserIdForUpdate`.
    - Mục 2 và dòng mở đầu: sửa "5 service" → "6 service", "28 test" → "33 test".
  - Các file khác trong `docs/` (`plan.md`, `Rubric.md`, `openapi-tuition.json`, `openapi-payment.json`, `MidtermVIHK12627.md`) không liệt kê port/service theo dạng bảng/sơ đồ kiến trúc tổng thể (hoặc là đề bài gốc/rubric không nên sửa) — **không sửa**, đúng phạm vi được giao.
  - Phát hiện phụ (KHÔNG sửa, chỉ ghi nhận vì ngoài phạm vi Phase 6): `README.md` mục 5.1 ghi response login là `{token, userId, email, balance}` nhưng field thực tế là `accessToken` (khớp đúng `docs/openapi-auth.json` và code) — đây là lỗi đánh máy có từ TRƯỚC lượt tách account-service, không liên quan tới việc tách service, để nguyên chờ người dùng xác nhận có muốn sửa không.

  ### D. Dọn dẹp

  - `git status --short`: 28 dòng thay đổi, toàn bộ đúng như hai đợt làm trước để lại (sửa/xoá file `.java` trong `auth-service`, thêm mới `account-service/`, `TODO_Account_Service.md`, các file `client`/`config`/`dto` mới của `auth-service`) + 1 file mới sửa lượt này (`WebConfig.java` đã nằm trong `account-service/` chưa track nên không hiện riêng). **Không có `target/`, `BOOT-INF/`, `.env` nào lọt vào danh sách** — đã kiểm tra `account-service/target/` bị `.gitignore` (`target/`) chặn đúng (`git status --ignored` xác nhận `!! account-service/target/`).
  - Không `git add`/`commit` gì (đúng ràng buộc — chỉ kiểm tra, không commit khi chưa được yêu cầu rõ).

  ### Kết luận tổng thể dự án tách `account-service`

  **Toàn bộ 7 phase (0-6) đã hoàn thành**, có bằng chứng thật (test output + log Docker + kết quả gọi API qua network thật) ở từng bước, kể cả 1 bug thật phát sinh khi chạy Docker thật (403 silent-fail do lỗi wiring `@Value` trong `account-service/config/WebConfig.java`) đã được phát hiện và sửa, verify lại bằng cả `mvn test` (6/6 pass) lẫn gọi API thật qua Docker. Dữ liệu demo (số dư 100tr/15tr của `524h0088`/`524h0456`) được xác nhận nguyên vẹn sau toàn bộ quá trình test (kể cả sau khi test debit/credit thật, đã chủ động hoàn tiền lại).

  Việc còn lại CHỦ ĐÍCH chưa làm (xem mục "Việc CHƯA làm — chờ lệnh người dùng" ở trên, không phải thiếu sót): đổi email demo thật sang `@example.com`, và "clean" email khỏi lịch sử git — cả hai chờ người dùng chủ động yêu cầu ở lượt sau. Ngoài ra cột `balance` rác trong bảng `users` của `auth-service` (câu hỏi mở #3) vẫn cố ý để nguyên, chưa `DROP COLUMN` — cũng chờ người dùng quyết định.
