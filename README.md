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

| Username | Password | Số dư ban đầu | Ghi chú |
|---|---|---|---|
| `524h0088` | `123456` | 100.000.000 | Happy path — học phí `524H0088` là 8.500.000, đủ tiền |
| `524h0456` | `123456` | 15.000.000 | Học phí `524H0456` là 20.000.000 → dùng để test `409` thiếu số dư |

MSSV có sẵn để test (`tuition-service/src/main/resources/data.sql`): `524H0088`, `524H0100` (nợ 2 kỳ),
`524H0123` (đã đóng hết → 404), `524H0456` (số tiền lớn), `524H0789` (đã đóng kỳ cũ, còn nợ kỳ mới).

**Lấy OTP khi demo** (khỏi cấu hình SMTP thật): OTP lưu ở Redis, key `otp:<transactionId>`.

```bash
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> GET "otp:<transactionId>"
# quên transactionId thì scan:
docker exec ibanking-redis redis-cli -a <REDIS_PASSWORD> --scan --pattern "otp:*"
```

OTP hết hạn sau 5 phút; `initiate` giới hạn **3 lần/giờ/user**, `verify-otp` sai OTP tối đa **3 lần/giao dịch**.

### 6.4 Endpoint hạ tầng

- RabbitMQ management UI: `http://localhost:15672` (user/pass theo `.env`)
- Postgres: `localhost:5432`, 3 database `authdb`/`tuitiondb`/`paymentdb`

---

## 7. Cấu trúc thư mục

```
├── api-gateway/          Spring Cloud Gateway — JWT filter + routing + CORS
├── auth-service/         Login, JWT, users, số dư (debit/credit), sổ cái balance_entries
├── tuition-service/      Sinh viên, học phí, mark-paid
├── payment-service/      Điều phối saga thanh toán, OTP, rate-limit, lịch sử giao dịch
├── notification-service/ Consumer RabbitMQ, gửi email
├── docker-compose.yml    Toàn bộ hạ tầng + 5 service
├── init-db.sql           Tạo 3 database lúc Postgres khởi động lần đầu
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

## 8. Ghi chú cho người đọc code lần đầu (kể cả AI agent)

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
