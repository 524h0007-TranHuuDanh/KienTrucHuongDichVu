# iBanking — Phân hệ Đóng học phí

Đồ án giữa kỳ môn **SOA (CS504070)** — hiện thực phân hệ thanh toán học phí của ứng dụng iBanking bằng
kiến trúc **Microservices**. Người dùng đăng nhập, tra cứu học phí theo MSSV, xác nhận thanh toán bằng OTP
gửi qua email, và toàn bộ luồng đảm bảo tính nhất quán dữ liệu khi có nhiều giao dịch chạy đồng thời.

Đề bài gốc: [`docs/MidtermVIHK12627.md`](docs/MidtermVIHK12627.md).

> Repo này là **backend thuần** (không có frontend). Đề bài yêu cầu giao diện Web nhưng thư mục hiện tại
> chỉ có 5 service Spring Boot + hạ tầng Docker Compose.

---

## 1. Kiến trúc

```
                        (JWT bắt buộc, trừ /api/auth/login)
        Client  ──────────────────▶  api-gateway :8080  (Spring Cloud Gateway)
                                          │  xác thực JWT, forward + set header X-User-Id
                ┌─────────────────────────┼──────────────────────────┐
                ▼                         ▼                          ▼
        auth-service :8081       tuition-service :8082       payment-service :8083
        (users, số dư,            (sinh viên, học phí,        (điều phối saga
         phát hành JWT)            đánh dấu đã đóng)           OTP → debit → mark-paid)
                │                         │                          │
                └──────────── X-Internal-Api-Key (service gọi service) ───┘
                                                                      │
                                                                      ▼
                                                          RabbitMQ (email_queue)
                                                                      │
                                                                      ▼
                                                       notification-service :8084
                                                          (nghe queue, gửi email SMTP)

Hạ tầng dùng chung: PostgreSQL (1 instance, 3 database: authdb / tuitiondb / paymentdb),
Redis (OTP + rate-limit + Redisson distributed lock), RabbitMQ (hàng đợi email).
```

Chỉ `api-gateway` (8080) và `notification-service` (8084) publish port ra host; ba service còn lại chỉ
gọi được từ trong mạng Docker nội bộ — xem `docker-compose.yml`.

### Vai trò từng service

| Service | Port | Database | Trách nhiệm chính |
|---|---|---|---|
| `api-gateway` | 8080 | — | Cổng vào duy nhất, verify JWT (`GlobalFilter`), route theo path, CORS cho frontend |
| `auth-service` | 8081 | `authdb` | Đăng nhập, phát JWT, quản lý thông tin + **số dư** người dùng, ghi sổ cái `balance_entries` |
| `tuition-service` | 8082 | `tuitiondb` | Nguồn dữ liệu học phí duy nhất: tra cứu theo MSSV, đánh dấu đã đóng (`mark-paid`) |
| `payment-service` | 8083 | `paymentdb` (chỉ bảng `transactions`) | **Điều phối** luồng thanh toán: OTP, saga trừ tiền + xác nhận học phí, lịch sử giao dịch |
| `notification-service` | 8084 | — | Consumer RabbitMQ, gửi email OTP / xác nhận thanh toán qua SMTP |

`payment-service` **không** có bảng `users`/`tuitions` cục bộ — nó gọi HTTP sang `auth-service` và
`tuition-service` để đọc/ghi, tự nó chỉ lưu bảng `transactions` để điều phối saga.

---

## 2. Công nghệ

- **Java 17**, **Spring Boot 3.2.4** (Spring Web, Spring Cloud Gateway, Spring Data JPA, Spring Security,
  Spring Data Redis, Spring AMQP, Spring Mail)
- **PostgreSQL 15**, **Redis 7**, **RabbitMQ 3** (management plugin)
- **JJWT 0.11.5** (tự ký/verify JWT, không dùng Keycloak/Auth0)
- **Redisson 3.27.2** — distributed lock cấp tài khoản khi xác thực OTP
- **Docker Compose** để chạy toàn bộ hệ thống

---

## 3. Xác thực & phân quyền

Hệ thống dùng **hai cơ chế xác thực song song**, đừng nhầm lẫn khi đọc code:

1. **JWT người dùng cuối** — phát bởi `auth-service` lúc login, gateway verify ở `JwtAuthenticationFilter`
   (`api-gateway/.../filter/JwtAuthenticationFilter.java`), set header `X-User-Id` rồi mới forward xuống
   service đích. Mỗi service tự parse JWT lại (không tin tưởng mù `X-User-Id`) để lấy `userId` gắn vào
   `HttpServletRequest` attribute.
2. **`X-Internal-Api-Key`** — key dùng chung (`INTERNAL_API_KEY` trong `.env`), dùng cho các lời gọi
   **service-to-service** không đi qua gateway (`payment-service` → `auth-service`/`tuition-service`).
   Xem `InternalApiKeyFilter` ở cả `auth-service` và `tuition-service`:
   - `POST /api/auth/users/{id}/debit|credit` — **bắt buộc** internal key, JWT không thay thế được.
   - `GET /api/auth/users/{id}` — chấp nhận **internal key HOẶC** JWT của chính chủ (owner-check nằm ở
     `AuthController.enforceOwnershipOrInternal`).

Chỉ đường `POST /api/auth/login` là public; mọi path khác qua gateway đều cần `Authorization: Bearer <jwt>`.

---

## 4. Luồng nghiệp vụ chính (payment saga)

```
POST /api/payments/initiate {mssv}
  1. Check rate-limit OTP (tối đa 3 lần/giờ/user)                    -> 429-style lỗi nếu vượt
  2. GET tuition-service /api/tuition/{mssv}   (khoản chưa đóng gần nhất)
  3. GET auth-service   /api/auth/users/{userId}  (kiểm tra số dư đủ)
  4. Tạo Transaction(status=PENDING), sinh OTP (SecureRandom, 6 số), lưu Redis TTL 5 phút
  5. Publish email OTP lên RabbitMQ (email_queue) -> notification-service gửi mail
  => trả về transactionId

POST /api/payments/verify-otp {transactionId, otp}
  1. Giành Redisson lock theo userId (chống 2 giao dịch cùng tài khoản chạy song song)
  2. Đọc lại Transaction TRONG lock, so khớp OTP (constant-time compare)
  3. transaction.status = PROCESSING (ghi ý định trước khi gọi mạng)
  4. POST auth-service /debit {amount, transactionId}      -- idempotent theo transactionId
       409 -> FAILED "số dư không đủ" (dừng, chưa mất gì)
       timeout -> retry x2 -> vẫn fail: giữ PROCESSING, cần đối soát tay
  5. POST tuition-service /{tuitionId}/mark-paid {transactionId} -- idempotent theo transactionId
       200 -> SUCCESS, gửi email xác nhận
       409/404 -> gọi /credit HOÀN TIỀN ĐẦY ĐỦ -> FAILED
       timeout -> retry x2 -> vẫn fail: GET lại trạng thái thật rồi mới quyết định hoàn tiền hay SUCCESS
  6. Xoá OTP khỏi Redis, clear rate-limit, nhả lock
```

**Cơ chế chống race (2 tình huống bắt buộc trong đề bài):**

| Tình huống | Cơ chế chặn |
|---|---|
| Nhiều giao dịch cùng lúc trên **cùng một tài khoản** | Redisson distributed lock `lock:account:{userId}` trong `verify-otp` |
| Nhiều người cùng đóng **cùng một khoản học phí** | `tuition-service.markPaid()` dùng `@Lock(PESSIMISTIC_WRITE)` + re-check `paid` trong lock + `UNIQUE(transaction_id)` — chỉ 1 request thắng, các request sau nhận `409` và được hoàn tiền |

Idempotency của `debit`/`credit` dựa trên bảng sổ cái `balance_entries` với ràng buộc
`UNIQUE(transaction_id, type)` ở `auth-service` — retry mạng không bao giờ trừ/hoàn tiền hai lần.

Chi tiết đầy đủ (mã giả, kịch bản race, bảng quyết định) nằm ở [`docs/plan.md`](docs/plan.md) mục 5.
**Lưu ý:** `plan.md` là tài liệu thiết kế lúc lập kế hoạch — một vài chi tiết (ví dụ cơ chế xác thực
service-to-service) đã đổi so với lúc viết plan (khi đó dự định forward JWT, nhưng code thực tế dùng
`X-Internal-Api-Key`, xem mục 3 ở trên). Khi có mâu thuẫn, **code là nguồn sự thật**.

---

## 5. REST API

### 5.1 `auth-service` — `/api/auth`

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/login` | public | `{username, password}` → `{token, userId, email, balance}` |
| GET | `/users/{id}` | JWT (chính chủ) hoặc internal key | Thông tin user: id, email, phone, balance |
| POST | `/users/{id}/debit` | internal key only | `{amount, transactionId}` → trừ tiền, idempotent |
| POST | `/users/{id}/credit` | internal key only | `{amount, transactionId}` → hoàn tiền, idempotent, từ chối nếu chưa từng debit |

### 5.2 `tuition-service` — `/api/tuition`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/{mssv}` | Khoản chưa đóng có `due_date` sớm nhất (nợ cũ trả trước); `404` nếu không có SV hoặc đã đóng hết |
| GET | `/{mssv}/all` | Toàn bộ khoản học phí của MSSV, sắp theo `due_date` |
| GET | `/id/{id}` | Chi tiết 1 khoản theo UUID |
| POST | `/{id}/mark-paid` | `{transactionId}` → đánh dấu đã đóng, idempotent, `409` nếu đã bị transaction khác đóng |

### 5.3 `payment-service` — `/api/payments`

| Method | Path | Mô tả |
|---|---|---|
| POST | `/initiate` | `{mssv}` → tạo giao dịch PENDING, gửi OTP qua email, trả `transactionId` |
| POST | `/verify-otp` | `{transactionId, otp}` → chạy saga thanh toán, trả kết quả |
| GET | `/history` | Lịch sử giao dịch của user đang đăng nhập (mới thêm — đáp ứng đặc tả mục 1 "Lịch sử các giao dịch đã thực hiện") |

Toàn bộ API trên (trừ `/login`) đi qua gateway `:8080` và cần header `Authorization: Bearer <jwt>`.

---

## 6. Chạy dự án

### 6.1 Chuẩn bị

```bash
cp .env.example .env
# điền JWT_SECRET (base64, tối thiểu 64 byte), INTERNAL_API_KEY, mật khẩu DB/Redis/RabbitMQ
# EMAIL_USER / EMAIL_PASS có thể để trống khi demo (xem 6.3 cách lấy OTP không cần email)
```

### 6.2 Build & khởi chạy

```bash
# build từng service (Dockerfile của mỗi service COPY sẵn target/*.jar)
mvn -f auth-service/pom.xml package -DskipTests
mvn -f tuition-service/pom.xml package -DskipTests
mvn -f payment-service/pom.xml package -DskipTests
mvn -f notification-service/pom.xml package -DskipTests
mvn -f api-gateway/pom.xml package -DskipTests

docker compose up -d --build
```

Lần đầu chạy (hoặc sau khi đổi schema): `docker compose down -v` trước để Postgres tạo lại volume sạch —
`init-db.sql` chỉ tạo 3 database (`authdb`, `tuitiondb`, `paymentdb`) lúc volume còn trống.

### 6.3 Tài khoản & dữ liệu demo

Seed tự động lúc khởi động (không cần gọi API nào):

**Tài khoản đăng nhập** (`auth-service/.../config/DemoDataSeeder.java`):

| Username | Password | Số dư ban đầu | Dùng để |
|---|---|---|---|
| `524h0088` | `123456` | 100.000.000 | Happy path — đủ tiền cho mọi khoản học phí demo |
| `524h0456` | `123456` | 15.000.000 | Test `409` thiếu số dư khi đóng cho MSSV `524H0004` (20.000.000) |

> `username` là tài khoản iBanking, **không phải MSSV**. Ai đăng nhập cũng có thể đóng học phí cho bất kỳ
> MSSV nào — đúng nghiệp vụ "người thân đóng hộ" của đề bài.

**MSSV để tra cứu** (`tuition-service/src/main/resources/data.sql`):

| MSSV | Họ tên | `GET /api/tuition/{mssv}` trả về | Dùng để |
|---|---|---|---|
| `524H0001` | Tran Huu Danh | HK1-2526 — 8.500.000 | Happy path |
| `524H0002` | Ta Nguyen Thanh Quy | HK2-2425 — 5.000.000 | Nợ 2 kỳ (còn HK1-2526 9.200.000) → kiểm tra ưu tiên `due_date` cũ nhất |
| `524H0003` | Le Minh Anh | — (`404`) | Đã đóng hết → test "không còn khoản chưa đóng" |
| `524H0004` | Pham Thi Mai | HK1-2526 — 20.000.000 | Vượt số dư của `524h0456` → test `409` thiếu số dư |
| `524H0005` | Vo Quoc Bao | HK1-2526 — 6.500.000 | Kỳ cũ HK2-2425 đã đóng → kiểm tra bỏ qua khoản đã thanh toán |

**Demo concurrency:** cho `524h0088` và `524h0456` cùng `initiate` rồi `verify-otp` trên cùng một MSSV
(ví dụ `524H0001`) — chỉ một giao dịch `SUCCESS`, giao dịch còn lại nhận `409` và được hoàn tiền tự động.

**Lấy OTP khi demo** (khỏi cấu hình SMTP thật): OTP lưu ở Redis, key `otp:<transactionId>`.

```bash
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> GET "otp:<transactionId>"
# quên transactionId thì scan:
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> --scan --pattern "otp:*"
```

OTP hết hạn sau 5 phút; `initiate` giới hạn **3 lần/giờ/user**, `verify-otp` sai OTP tối đa **3 lần/giao dịch**
(cộng thêm giới hạn **8 lần thất bại/giờ/user** tính chung mọi giao dịch).

Response khi bị chặn có kèm số liệu để FE hiển thị, không cần đoán:
- `429` (quá hạn gửi/thử OTP) → header `Retry-After` + body `retryAfterSeconds` (số giây còn phải chờ).
- `409` (OTP sai nhưng chưa hết lượt) → body `remainingAttempts` (số lần thử còn lại).

**Reset rate-limit khi test** (chỉ dùng khi dev/demo — hệ thống không cho tự đăng ký nên nhiều người
thường phải dùng chung 1-2 tài khoản demo, dễ bị "hết lượt oan" do người khác test trước đó):

```bash
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> DEL \
  "otp:request:<userId>" "otp:attempt:<transactionId>" "otp:userfail:<userId>"
# quên userId/transactionId thì scan:
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> --scan --pattern "otp:*"
```

### 6.4 Endpoint hạ tầng

- RabbitMQ management UI: `http://localhost:15672` (user/pass theo `.env`)
- Postgres: `localhost:5432`, 3 database `authdb`/`tuitiondb`/`paymentdb`

---

## 7. Kiểm thử tự động

### 7.1. Yêu cầu

Test dùng **Testcontainers**: mỗi lần chạy sẽ tự dựng Postgres (và Redis cho `payment-service`)
trong Docker rồi tự xoá. Vì vậy **Docker phải đang chạy**, nhưng KHÔNG cần `docker compose up`
— test hoàn toàn độc lập với hệ thống đang chạy, dùng cổng ngẫu nhiên nên không đụng nhau.

Cố ý **không dùng H2**: `TuitionRepository.findFirstUnpaid` là native query Postgres (`LIMIT 1`),
và `SELECT ... FOR UPDATE` trên H2 không phản ánh đúng hành vi khoá — test đồng thời sẽ *xanh giả*.

### 7.2. Chạy test

```bash
mvn -f auth-service/pom.xml test        # 10 test
mvn -f tuition-service/pom.xml test     # 11 test
mvn -f payment-service/pom.xml test     #  7 test
```

Mỗi module chạy khoảng 10–15 giây. Không có parent pom aggregator nên phải chạy từng module.
Surefire đã được cấu hình chạy cả `*Test` lẫn `*IT`.

| Module | Test | Nội dung chính |
|---|---|---|
| `auth-service` | `BalanceServiceTest` (5) | Trừ tiền, idempotency theo `transaction_id`, thiếu số dư, hoàn tiền sai, giao dịch đã chốt |
| | `BalanceConcurrencyIT` (1) | **Tình huống tranh chấp 1** — 10 thread cùng trừ tiền một tài khoản |
| | `AuthControllerIT` (4) | Đăng nhập đúng/sai, JWT người khác → 403, thiếu internal key → 403 |
| `tuition-service` | `TuitionServiceTest` (7) | Học phí đến hạn sớm nhất, không tìm thấy, đã đóng hết, `mark-paid` + idempotency |
| | `TuitionMarkPaidConcurrencyIT` (1) | **Tình huống tranh chấp 2** — 10 thread cùng gạch nợ một khoản học phí |
| | `TuitionControllerIT` (3) | 401 khi thiếu JWT, 403 khi thiếu internal key, 200 khi đủ |
| `payment-service` | `PaymentServiceTest` (5) | Sinh OTP + TTL, sai OTP 3 lần → FAILED, luồng thành công, **saga bù trừ**, chống xử lý lặp |
| | `RateLimitTest` (2) | Quá 3 OTP/giờ → 429; không trừ quota khi gửi OTP thất bại |

### 7.3. Cô lập dữ liệu

Test đa luồng **không dùng được `@Transactional`** (thread con chạy trong transaction khác nên
không thấy dữ liệu chưa commit của test, và rollback cũng không dọn được thứ thread con đã commit).
Do đó không test class nào dùng `@Transactional`; thay vào đó mỗi test **tự tạo dữ liệu riêng**
với `UUID.randomUUID()` và tự dọn trong `@BeforeEach`/`@AfterEach`, không truncate bảng.
Các dòng seed của `data.sql` (524H0002, 524H0003) chỉ được **đọc**, không bao giờ bị sửa.

Để 10 thread chạy **thật sự đồng thời** (vòng lặp `submit()` thông thường có thể chạy tuần tự,
khiến test xanh cả khi khoá đã hỏng), hai test tranh chấp dùng *starting gate* 3 latch:
thread pool đủ 10 chỗ, `ready` để xác nhận cả 10 thread đã tới vạch, `start` để thả cùng lúc,
`done` để bắt deadlock thay vì treo.

### 7.4. Kiểm thử đồng thời đầu-cuối (E2E)

```bash
docker compose up -d
bash scripts/concurrency-test.sh
```

Script gọi thật qua gateway `:8080`, lấy OTP từ Redis, tự dựng lại dữ liệu fixture trước mỗi
kịch bản (nên chạy lại được nhiều lần), in bảng PASS/FAIL và trả exit code khác 0 nếu có kịch bản hỏng.

- **Kịch bản A** — một tài khoản, 2 giao dịch song song, tổng tiền vượt số dư → đúng 1 SUCCESS,
  số dư không âm, sổ sách khớp.
- **Kịch bản B** — hai tài khoản cùng đóng một khoản học phí → đúng 1 SUCCESS, học phí chỉ gạch
  nợ một lần, người thua được hoàn tiền đủ (script kiểm tận bút toán `balance_entries`: phải có
  đủ cặp DEBIT + CREDIT bằng nhau).

### 7.5. Chứng minh test tranh chấp không "xanh giả"

Cách kiểm chứng test thật sự có giá trị — gỡ cơ chế bảo vệ rồi chạy lại, test **phải đỏ**:

| Gỡ gì | Kết quả |
|---|---|
| `@Lock(PESSIMISTIC_WRITE)` khỏi `UserRepository.findByIdForUpdate` | `BalanceConcurrencyIT` ĐỎ — `expected: 5 but was: 10`, cả 10 thread trừ được 20.000.000 từ số dư 10.000.000 |
| `@Lock` khỏi `TuitionRepository.findByIdForUpdate` **và** `@Version` khỏi `Tuition` | `TuitionMarkPaidConcurrencyIT` ĐỎ — `expected: 1 but was: 10` |

Lưu ý quan trọng: với `tuition-service`, **chỉ gỡ `@Lock` là chưa đủ để test đỏ** — `@Version`
(optimistic lock) vẫn giữ được bất biến "một người thắng", chỉ khác loại exception mà 9 thread thua
nhận được (`ObjectOptimisticLockingFailureException` thay vì `TuitionAlreadyPaidException`).
Nói cách khác khoản học phí được bảo vệ bằng **hai lớp độc lập**.

### 7.6. Hai vướng mắc môi trường đã xử lý sẵn

- **Docker Engine ≥ 25**: Testcontainers 1.19.7 đàm phán Docker API 1.32 trong khi engine mới yêu cầu
  tối thiểu 1.40 → lỗi `Could not find a valid Docker environment`. Các lớp base test đã tự đặt
  `api.version=1.41` nếu chưa được cấu hình.
- **JDK > 22**: Byte Buddy đi kèm Spring Boot 3.2.4 chưa biết bytecode mới nên `@MockBean` lỗi.
  `payment-service` đã bật `-Dnet.bytebuddy.experimental=true` trong surefire (chỉ phạm vi test).

---

## 8. Cấu trúc thư mục

```
├── api-gateway/          Spring Cloud Gateway — JWT filter + routing + CORS
├── auth-service/         Login, JWT, users, số dư (debit/credit), sổ cái balance_entries
├── tuition-service/      Sinh viên, học phí, mark-paid
├── payment-service/      Điều phối saga thanh toán, OTP, rate-limit, lịch sử giao dịch
├── notification-service/ Consumer RabbitMQ, gửi email
├── docker-compose.yml    Toàn bộ hạ tầng + 5 service
├── init-db.sql           Tạo 3 database lúc Postgres khởi động lần đầu
├── scripts/
│   └── concurrency-test.sh   Kiểm thử E2E 2 tình huống tranh chấp (mục 7.4)
├── .env.example          Mẫu biến môi trường (copy thành .env)
└── docs/
    ├── MidtermVIHK12627.md      Đề bài gốc
    ├── plan.md                  Kế hoạch thiết kế tuition-service + saga (ERD, API contract, ...)
    ├── LOI-PAYMENT-SERVICE.md   Danh sách lỗi đã phát hiện ở payment-service (P-01 → P-2x)
    ├── CHANGES-AUTH-PAYMENT.md  Nhật ký thay đổi khi tách số dư/học phí ra khỏi payment-service
    └── TIEN-DO-FIX-BUG.md       Nhật ký tiến độ fix bug (dùng để resume phiên làm việc với AI)
```

Mỗi service theo cấu trúc Spring Boot chuẩn:
`controller/` → `service/` → `repository/` (JPA) + `entity/`, cùng `dto/`, `exception/`, `config/`,
`client/` (RestTemplate client gọi service khác, chỉ có ở `payment-service`).

---

## 9. Ghi chú cho người đọc code lần đầu (kể cả AI agent)

- **`code là nguồn sự thật`**, không phải `docs/plan.md` hay `MidtermVIHK12627.md`. Các file trong `docs/`
  là nhật ký thiết kế/nhật ký sửa lỗi tại từng thời điểm, một số quyết định đã thay đổi sau đó (ví dụ mục 4
  ở trên). Đừng suy luận trạng thái hiện tại của API/DB chỉ từ các file đó — hãy đọc `controller`/`entity`.
- `payment-service` **không** có `entity/User` hay `entity/Tuition` — nếu thấy comment/tài liệu cũ nhắc tới
  chúng, đó là trạng thái đã bị loại bỏ (Phương án B, xem `docs/plan.md` mục 0).
- Số dư và học phí là **hai nguồn dữ liệu độc lập** (`auth-service`, `tuition-service`); `payment-service`
  chỉ điều phối qua HTTP, không có transaction DB xuyên service — tính nhất quán dựa vào saga bù trừ
  (mục 4) chứ không phải 2-phase commit.
- Nhánh `tuition-service` (branch git hiện tại) là nhánh đang phát triển tích cực nhất; xem
  `git log --oneline` để biết các fix gần nhất trước khi giả định hành vi của một đoạn code.
