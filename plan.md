# Kế hoạch hiện thực `tuition-service`

> **Trạng thái:** Nhiệm vụ 1 (plan) + Nhiệm vụ 2 (review BA) **đã xong**. Mọi vấn đề 🔴 **đã được chốt**.
> Chưa viết dòng code nào — chờ cổng (c).
>
> **Phạm vi được phép sửa (đã mở rộng theo các QĐ ngày 30/08/2026):**
> `tuition-service/` · `payment-service/` · `auth-service/` (chỉ phần số dư).
> **Không** sửa: `init-db.sql`, `api-gateway/`, `notification-service/`, `docker-compose.yml`.
>
> Nguồn đối chiếu: code thực tế trong repo. File đặc tả `MidtermVIHK12627.md` **không tồn tại trong repo** —
> mọi khẳng định dưới đây suy ra từ code, không suy ra từ đặc tả.

---

## 0. Quyết định đã chốt

| # | Câu hỏi | Chốt | Ảnh hưởng |
|---|---------|------|-----------|
| 1 | Có được sửa `payment-service`? | **CÓ** — làm **Phương án B** | `tuition-service` giữ `tuitiondb`, là source of truth duy nhất cho học phí; `payment-service` bỏ bảng `tuitions` cục bộ, gọi HTTP `mark-paid` |
| 2 | SV nợ được nhiều học kỳ? | **CÓ** | **Bỏ** partial unique index `(mssv) WHERE paid=false`; thêm `due_date`; `GET` trả khoản chưa đóng có `due_date` sớm nhất |
| 3 | Lấy OTP khi demo? | **Đọc từ Redis** bằng `redis-cli` | Không sửa cấu hình email, không đụng `notification-service` |
| 4 | Đóng hộ người khác? | **CHO PHÉP** | Không map `auth.users.username ↔ students.mssv`, không kiểm tra gì thêm ở `initiate` |
| 5 | `initiate` có trả `studentName`? | **KHÔNG** | **QĐ-4 đóng lại.** Không sửa `TuitionInfo`/`PaymentInitResponse`. `studentName` vẫn có trong `GET /api/tuition/{mssv}` |
| 6 | Số dư lưu ở đâu? | **B2 — `auth-service` là nguồn duy nhất** | Thêm `debit`/`credit` vào `auth-service`; `payment-service` **xóa** `entity/User` + `UserRepository` |
| 7 | Xác thực giữa các service? | **Chuyển tiếp JWT của người dùng** (quyết định kỹ thuật, xem [Mục 8](#8-ba-13--xác-thực-service-to-service-phát-hiện-mới)) | Thêm interceptor vào `RestTemplateConfig` của `payment-service` |

---

## 1. Hiện trạng (đã kiểm chứng bằng code)

| # | Sự thật | Bằng chứng |
|---|---------|------------|
| 1 | `tuition-service` chỉ có `main()` | `tuition-service/.../TuitionApplication.java` |
| 2 | `payment-service` **đọc** học phí qua `GET http://tuition-service:8082/api/tuition/{mssv}` | `TuitionServiceClient.java:16` |
| 3 | `payment-service` **ghi** `paid=true` vào bảng **cục bộ** `paymentdb.tuitions`, không gọi lại tuition-service | `PaymentService.java:124,146` |
| 4 | Không có code nào INSERT vào `paymentdb.tuitions` → bảng luôn rỗng → NPE | `grep` toàn bộ `payment-service/src` |
| 5 | Không có code nào INSERT vào `paymentdb.users` → bảng luôn rỗng → NPE (nổ **trước** #4) | `PaymentService.java:123,133` |
| 6 | `TuitionInfo` chỉ có `id, mssv, amount, paid` | `dto/TuitionInfo.java` |
| 7 | `PaymentInitRequest` ràng buộc MSSV `^[0-9]{6}$` — chặn `524H0088` | `dto/PaymentInitRequest.java:15` |
| 8 | Gateway bắt buộc JWT mọi path trừ `/api/auth/login`, `/api/auth/fix` | `gateway/filter/JwtAuthenticationFilter.java:24` |
| 9 | `payment-service` gọi tuition-service & auth-service **trực tiếp**, bỏ qua gateway, **không kèm token** | `TuitionServiceClient.java:13`, `AuthServiceClient.java:16` |
| 10 | `EMAIL_USER`/`EMAIL_PASS` rỗng → email OTP vào DLQ; `PaymentService` chỉ log email đã che, **không log OTP** | `.env`, `PaymentService.java:87` |
| 11 | **`auth-service` bắt buộc auth cho `/api/auth/users/**`** (`.anyRequest().authenticated()`), nhưng `AuthServiceClient` gọi **không có token** → `401/403` | `auth/config/SecurityConfig.java:52`, `AuthServiceClient.java:16` |

**Luồng hiện tại đứt ở 4 chỗ, không phải 2:**

```
POST /api/payments/initiate  {"mssv":"524H0088"}
  [1] @Pattern ^[0-9]{6}$          -> 400 Bad Request        <-- chặn ngay tại validation
  [2] tuitionServiceClient.get()   -> 404 (chưa có endpoint)  <-- sẽ được hiện thực
  [3] authServiceClient.getUserInfo() -> 401/403              <-- MỚI PHÁT HIỆN (BA-13)

POST /api/payments/verify-otp
  [4a] userRepository.findByIdForUpdate()    -> null -> NPE tại L133
  [4b] tuitionRepository.findByIdForUpdate() -> null -> NPE tại L136
```

---

## 2. Entity / ERD

### 2.1 Sơ đồ `tuitiondb` (tuition-service)

```
+----------------------------+          +------------------------------------------+
| students                   |          | tuitions                                 |
+----------------------------+          +------------------------------------------+
| id           UUID    PK    |          | id             UUID    PK                |
| mssv         VARCHAR(16)   |<--------+| mssv           VARCHAR(16) FK NOT NULL   |
|              UNIQUE NOT NULL|   1    N | semester       VARCHAR(16) NOT NULL      |
| full_name    VARCHAR(128)  |          | due_date       DATE        NOT NULL      |
| email        VARCHAR(128)  |          | amount         NUMERIC(12,2) NOT NULL    |
| faculty      VARCHAR(128)  |          | paid           BOOLEAN NOT NULL DEFAULT F |
| created_at   TIMESTAMP     |          | paid_at        TIMESTAMP NULL            |
+----------------------------+          | transaction_id UUID NULL UNIQUE          |
                                        | version        INTEGER                   |
                                        | created_at     TIMESTAMP                 |
                                        +------------------------------------------+
```

### 2.2 Sơ đồ `authdb` (auth-service — phần thêm mới cho QĐ 6)

```
+----------------------------+          +------------------------------------------+
| users  (ĐÃ CÓ, giữ nguyên) |          | balance_entries  (MỚI - sổ cái + audit)  |
+----------------------------+          +------------------------------------------+
| id           UUID    PK    |<--------+| id             UUID    PK                |
| username     UNIQUE        |   1    N | user_id        UUID    FK NOT NULL       |
| password                   |          | transaction_id UUID    NOT NULL          |
| full_name / phone / email  |          | type           VARCHAR(8) NOT NULL       |
| balance      NUMERIC(15,2) |          |                  -- DEBIT | CREDIT        |
| created_at / updated_at    |          | amount         NUMERIC(15,2) NOT NULL    |
+----------------------------+          | created_at     TIMESTAMP                 |
                                        |                                          |
                                        | UNIQUE (transaction_id, type)  <-- chốt   |
                                        +------------------------------------------+
```

`UNIQUE (transaction_id, type)` là **cơ chế idempotency**: mỗi giao dịch chỉ trừ được 1 lần và hoàn được 1 lần.
Bảng này đồng thời là **audit log** cho dòng tiền — bắt buộc với nghiệp vụ tiền bạc.

### 2.3 `tuitions.mssv` vừa là FK vừa là cột dữ liệu

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "mssv", referencedColumnName = "mssv", nullable = false)
private Student student;
```

Một cột duy nhất, không trùng lặp dữ liệu, và giữ đúng tên cột `mssv` mà `TuitionInfo` mong đợi.

### 2.4 Vì sao cần `due_date` (phát hiện từ review BA)

QĐ 2-A yêu cầu trả **khoản chưa đóng cũ nhất**. Không thể sắp xếp bằng chuỗi `semester`:
`"HK1-2526" < "HK2-2425"` theo thứ tự chữ cái, **nhưng HK2-2425 mới là kỳ cũ hơn**.

**Rule sắp xếp chính thức:** `ORDER BY due_date ASC, semester ASC` (cột thứ hai phá thế hòa, đảm bảo kết quả tất định).

### 2.5 Ràng buộc

| Ràng buộc | Kiểu | Mục đích |
|-----------|------|----------|
| `students.mssv` | `UNIQUE NOT NULL` | MSSV là khóa nghiệp vụ |
| `tuitions(mssv, semester)` | `UNIQUE` | Mỗi SV chỉ có 1 khoản / học kỳ |
| `tuitions.transaction_id` | `UNIQUE` (nullable) | 1 giao dịch không đóng được 2 khoản |
| `tuitions.version` | `@Version` | Optimistic lock |
| `balance_entries(transaction_id, type)` | `UNIQUE` | Idempotency cho `debit`/`credit` |
| ~~`UNIQUE (mssv) WHERE paid=false`~~ | **ĐÃ BỎ** | Mâu thuẫn với QĐ 2-A (SV được nợ nhiều kỳ) |

> **Vì sao bỏ partial index vẫn an toàn:** yêu cầu "chỉ 1 người đóng thành công" **không** dựa vào index này,
> mà dựa vào pessimistic lock + re-check `paid` bên trong lock ở `tuition-service.markPaid()` (Mục 5.2).

---

## 3. Bảng REST API

### 3.1 `tuition-service` — `:8082`

| # | URI | Method | Request | Response 200 | Mã lỗi |
|---|-----|--------|---------|--------------|--------|
| 1 | `/api/tuition/{mssv}` | `GET` | path `mssv` | `{"id":"<uuid>","mssv":"524H0088","studentName":"Tran Huu Danh","semester":"HK1-2526","amount":8500000.00,"paid":false}` | `404` không thấy MSSV **hoặc** không còn khoản chưa đóng |
| 2 | `/api/tuition/{mssv}/all` | `GET` | path `mssv` | `[{...},{...}]` — mọi khoản, `ORDER BY due_date` | `404` không thấy MSSV |
| 3 | `/api/tuition/id/{id}` | `GET` | path `id` (UUID) | như #1 + `dueDate`, `paidAt`, `transactionId` | `404` không thấy id |
| 4 | `/api/tuition/{id}/mark-paid` | `POST` | `{"transactionId":"<uuid>"}` | `{"id":...,"paid":true,"paidAt":"...","transactionId":"..."}` | `404` · `409` đã đóng bởi transaction **khác** · `200` **idempotent** nếu đúng transaction đó |
| 5 | `/actuator/health` | `GET` | — | `{"status":"UP"}` | — |

### 3.2 `auth-service` — `:8081` (thêm mới, QĐ 6)

| # | URI | Method | Request | Response 200 | Mã lỗi |
|---|-----|--------|---------|--------------|--------|
| 6 | `/api/auth/users/{id}/debit` | `POST` | `{"amount":8500000.00,"transactionId":"<uuid>"}` | `{"userId":...,"balance":6500000.00}` | `404` user · `409` **số dư không đủ** · `200` **idempotent** nếu đã trừ bởi transaction này |
| 7 | `/api/auth/users/{id}/credit` | `POST` | `{"amount":8500000.00,"transactionId":"<uuid>"}` | `{"userId":...,"balance":15000000.00}` | `404` user · `409` chưa từng có `DEBIT` cho transaction này · `200` **idempotent** |

> Cả hai **bắt buộc JWT** (auth-service giữ nguyên `.anyRequest().authenticated()`) — xem Mục 8.

### 3.3 Ngữ nghĩa `GET /api/tuition/{mssv}` (chốt theo QĐ 2-A)

```sql
SELECT * FROM tuitions
WHERE mssv = UPPER(:mssv) AND paid = false
ORDER BY due_date ASC, semester ASC
LIMIT 1
```

- SV không tồn tại → `404` `{"message":"Không tìm thấy sinh viên với MSSV ..."}`
- SV tồn tại nhưng **đã đóng hết** → `404` `{"message":"Sinh viên ... không còn khoản học phí chưa đóng"}`
- SV nợ nhiều kỳ → trả **kỳ có `due_date` sớm nhất** (nợ cũ trả trước)

### 3.4 Khớp với `TuitionInfo` DTO của payment-service

`TuitionInfo` deserialize 4 field: `id (UUID)`, `mssv (String)`, `amount (BigDecimal)`, `paid (Boolean)`. ✅ Đủ.
Field thừa `studentName`, `semester` **an toàn** — Spring Boot mặc định tắt `FAIL_ON_UNKNOWN_PROPERTIES`.

Theo QĐ 5-B: **không** thêm `studentName` vào `TuitionInfo`/`PaymentInitResponse`. Frontend muốn hiện tên SV thì
gọi `GET /api/tuition/{mssv}` qua gateway (kèm JWT).

### 3.5 Hợp đồng idempotency (nền tảng của saga Mục 5)

**`mark-paid`:**

| Trạng thái | `transactionId` gửi lên | Kết quả |
|---|---|---|
| `paid = false` | bất kỳ | `200` — đánh dấu đã đóng |
| `paid = true`, `transaction_id` trùng | trùng | `200` — trả nguyên trạng, **không đổi gì** |
| `paid = true`, `transaction_id` khác | khác | `409 Conflict` |
| không tồn tại `id` | — | `404` |

**`debit` / `credit`:**

| Điều kiện | Kết quả |
|---|---|
| Chưa có `balance_entries(transactionId, DEBIT)` và đủ số dư | `200` — trừ tiền, ghi sổ cái |
| **Đã có** `balance_entries(transactionId, DEBIT)` | `200` — trả số dư hiện tại, **không trừ lần nữa** |
| Không đủ số dư | `409` |
| `credit` mà chưa từng `DEBIT` cho transaction đó | `409` — chống hoàn tiền khống |

### 3.6 Vì sao **không** có `unmark-paid`

Cân nhắc ban đầu là thêm endpoint bù trừ `unmark-paid`. **Đã loại bỏ.** Thao tác bù trừ qua mạng tự nó cũng có
thể fail → chỉ đẩy bài toán sang chỗ khác. Thay vào đó, saga Mục 5.3 xử lý nhánh timeout bằng **đọc lại trạng thái
thật** (`GET /api/tuition/id/{id}`) rồi mới quyết định hoàn tiền hay không.

---

## 4. Seed data — `tuition-service/src/main/resources/data.sql`

> ⚠️ Với `ddl-auto: update`, Spring Boot **mặc định KHÔNG chạy `data.sql`**. Phải thêm vào `application.yml`:
>
> ```yaml
> spring:
>   sql:
>     init:
>       mode: always
>   jpa:
>     defer-datasource-initialization: true
> ```
>
> `data.sql` chạy **mỗi lần khởi động** → mọi câu lệnh phải idempotent (`ON CONFLICT DO NOTHING`).

| MSSV | Họ tên | Học kỳ | `due_date` | Số tiền | `paid` | Mục đích test |
|------|--------|--------|-----------|---------|--------|---------------|
| `524H0088` | Tran Huu Danh | HK1-2526 | 2025-10-15 | 8.500.000 | `false` | **Luồng chính** — trùng `username` user trong auth-service |
| `524H0100` | Nguyen Thanh Quy | HK2-2425 | 2025-03-15 | 5.000.000 | `false` | **Nợ kỳ cũ** — `GET` phải trả kỳ này |
| `524H0100` | Nguyen Thanh Quy | HK1-2526 | 2025-10-15 | 9.200.000 | `false` | Kỳ mới — `GET` **KHÔNG** được trả kỳ này |
| `524H0123` | Le Minh Anh | HK1-2526 | 2025-10-15 | 7.800.000 | **`true`** | Test `404` "không còn khoản chưa đóng" |
| `524H0456` | Pham Thi Mai | HK1-2526 | 2025-10-15 | 20.000.000 | `false` | Test **số dư không đủ** (user có 15.000.000) → `409` từ `debit` |
| `524H0789` | Vo Quoc Bao | HK2-2425 | 2025-03-15 | 6.500.000 | **`true`** | Đã trả kỳ cũ... |
| `524H0789` | Vo Quoc Bao | HK1-2526 | 2025-10-15 | 6.500.000 | `false` | ...→ `GET` phải trả kỳ **mới** này |

UUID ghi cứng (literal) để test lặp lại được. Khoản `paid=true` seed kèm `paid_at` + `transaction_id` giả.

**Test case rút ra từ seed:**
- `GET /api/tuition/524H0100` → phải trả `HK2-2425`, `5.000.000` (không phải `9.200.000`)
- `GET /api/tuition/524H0789` → phải trả `HK1-2526` (bỏ qua kỳ cũ đã đóng)
- `GET /api/tuition/524h0088` (thường) → phải trả đúng như `524H0088` (chuẩn hóa `UPPER`)
- `GET /api/tuition/524H0123` → `404`
- `GET /api/tuition/999X9999` → `404`

---

## 5. Concurrency & Saga (Phương án B + QĐ 6)

### 5.1 Ai khóa cái gì

Với Phương án B, `payment-service` **không còn** bảng `tuitions` lẫn `users` cục bộ → Redisson lock và
`SELECT ... FOR UPDATE` của nó **không còn tác dụng**. Trách nhiệm chống race chuyển hẳn sang hai service chủ sở hữu dữ liệu.

| Tài nguyên | Chủ sở hữu | Cơ chế |
|---|---|---|
| Số dư | **`auth-service`** | `@Lock(PESSIMISTIC_WRITE)` trên `users` + `UNIQUE(transaction_id, type)` ở `balance_entries` |
| Khoản học phí | **`tuition-service`** | `@Lock(PESSIMISTIC_WRITE)` trên `tuitions` + re-check `paid` **trong** lock + `@Version` + `UNIQUE(transaction_id)` |
| Điều phối | `payment-service` | Saga + trạng thái `Transaction` |

> DB là **điểm đồng bộ duy nhất**. Redisson lock chỉ bảo vệ luồng đi qua payment-service; ai gọi thẳng `mark-paid`
> hoặc `debit` sẽ vượt qua nó. Vì vậy lock ở tầng DB của service chủ sở hữu là **bắt buộc**.

### 5.2 `tuition-service.markPaid()` — mã giả

```java
@Transactional
public TuitionDetailResponse markPaid(UUID id, UUID transactionId) {
    // SELECT ... FOR UPDATE  -> serialize mọi request trên cùng 1 khoản
    Tuition t = repo.findByIdForUpdate(id)
            .orElseThrow(() -> new TuitionNotFoundException(id));

    if (Boolean.TRUE.equals(t.getPaid())) {
        if (transactionId.equals(t.getTransactionId())) {
            return toDetail(t);                       // 200 - idempotent replay
        }
        throw new TuitionAlreadyPaidException(id);    // 409 - người khác đã đóng
    }

    t.setPaid(true);
    t.setPaidAt(LocalDateTime.now());
    t.setTransactionId(transactionId);
    return toDetail(repo.save(t));                    // 200
}
```

### 5.3 `auth-service.debit()` — mã giả

```java
@Transactional
public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
    if (ledgerRepo.existsByTransactionIdAndType(transactionId, DEBIT)) {
        return current(userId);                       // 200 - idempotent replay
    }
    User u = userRepo.findByIdForUpdate(userId)       // SELECT ... FOR UPDATE
            .orElseThrow(() -> new UserNotFoundException(userId));

    if (u.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException();     // 409
    }
    u.setBalance(u.getBalance().subtract(amount));
    try {
        ledgerRepo.save(new BalanceEntry(userId, transactionId, DEBIT, amount));
    } catch (DataIntegrityViolationException e) {
        // 2 request song song cùng transactionId cùng vượt qua existsBy ở trên
        // -> UNIQUE(transaction_id, type) chặn -> coi như replay
        return current(userId);
    }
    return toResponse(u);                             // 200
}
```

`credit()` tương tự, thêm điều kiện: chỉ hoàn khi **đã tồn tại** `DEBIT` cho `transactionId` đó (chống hoàn tiền khống).

### 5.4 Saga trong `payment-service.verifyOtpAndPay()`

**Thứ tự có chủ đích:** trừ tiền trước, đánh dấu học phí sau. Lý do: nếu `mark-paid` thất bại thì hoàn tiền là
thao tác **có thể xác minh được** (đọc lại sổ cái / trạng thái học phí). Nếu làm ngược lại, gỡ `paid` là thao tác
**không xác minh được** và dễ để lại học phí đã đóng mà không ai trả tiền.

```
1. Kiểm OTP (Redis)                                       - fail -> 400, chưa đụng gì
2. transaction.status = PROCESSING  (local commit)        - GHI Ý ĐỊNH trước mọi lời gọi mạng

3. POST auth-service /api/auth/users/{userId}/debit  {amount, transactionId}
     200 -> tiếp bước 4
     409 -> status = FAILED, "Số dư không đủ"                             [dừng, chưa mất gì]
     404 -> status = FAILED, "Không tìm thấy tài khoản"                   [dừng]
     timeout/5xx -> retry x2 (an toàn nhờ idempotency 3.5)
                 -> vẫn fail: status = PROCESSING, log ERROR, đối soát tay [dừng]

4. POST tuition-service /api/tuition/{tuitionId}/mark-paid  {transactionId}
     200 -> status = SUCCESS -> gửi email xác nhận                        [XONG]
     409 -> credit(userId, amount, transactionId)  HOÀN TIỀN
            -> status = FAILED, "Học phí đã được người khác thanh toán"
     404 -> credit(...) HOÀN TIỀN -> status = FAILED
     timeout/5xx -> retry x2 (an toàn nhờ idempotency 3.5)
                 -> vẫn fail: GET /api/tuition/id/{tuitionId} ĐỌC LẠI TRẠNG THÁI THẬT
                       transactionId == của mình -> thực ra ĐÃ thành công -> SUCCESS
                       paid == true, transactionId KHÁC -> người khác đã đóng xong
                                                    trong lúc mình retry
                                                 -> credit(...) HOÀN TIỀN -> FAILED
                       paid == false             -> credit(...) HOÀN TIỀN -> FAILED
                       không đọc được luôn       -> giữ PROCESSING, log ERROR, đối soát tay

5. Xóa OTP khỏi Redis, clear rate-limit attempts, nhả lock
```

**Bước "đọc lại" ở nhánh timeout là mấu chốt** — nó phân biệt "request chưa tới nơi" với "request đã commit nhưng
mất response", tránh hoàn tiền nhầm cho một khoản đã thực sự được đóng.

`PROCESSING` là trạng thái **duy nhất** cần can thiệp tay, chỉ xảy ra khi một service chết hẳn giữa chừng.

### 5.5 Kịch bản 2 người cùng đóng `524H0100` (khoản HK2-2425)

```
t0  A: initiate -> GET /api/tuition/524H0100 -> paid=false -> transaction TA, gửi OTP
t0  B: initiate -> GET /api/tuition/524H0100 -> paid=false -> transaction TB, gửi OTP
                   (!) CẢ HAI ĐỀU QUA - đúng: chưa trừ tiền, mới chỉ gửi OTP

t1  A: verify-otp -> debit(A, TA) 200 -> mark-paid{TA} -> FOR UPDATE -> paid=false
                                                       -> paid=true, tx=TA -> 200 -> SUCCESS
t2  B: verify-otp -> debit(B, TB) 200 -> mark-paid{TB} -> chờ lock -> paid=TRUE, tx=TA != TB
                                                       -> 409
                                      -> credit(B, TB) -> HOÀN TIỀN ĐẦY ĐỦ -> FAILED
```

→ **Chỉ 1 người thành công, người thua được hoàn tiền đầy đủ, số dư về đúng như cũ.**

### 5.6 Thay đổi `TransactionStatus`

```java
public enum TransactionStatus {
    PENDING,      // đã tạo, chờ OTP
    PROCESSING,   // MỚI - đã trừ tiền, chưa xác nhận được kết quả với service kia
    SUCCESS,
    FAILED
}
```

---

## 6. Thay đổi ở `payment-service` (đã được duyệt)

| # | File | Thay đổi | Lý do |
|---|------|----------|-------|
| 1 | `dto/PaymentInitRequest.java` | Regex → `^[0-9]{3}[A-Za-z][0-9]{4}$`, message → `"MSSV không đúng định dạng (VD: 524H0088)"` | **QĐ-2** — regex cũ chặn `524H0088` ngay tại validation |
| 2 | `config/RestTemplateConfig.java` | Thêm `ClientHttpRequestInterceptor` chuyển tiếp header `Authorization` của request hiện tại | **BA-13** (Mục 8) — sửa luôn `getUserInfo()` đang lỗi 401 |
| 3 | `client/AuthServiceClient.java` | Thêm `debit(userId, amount, transactionId)` và `credit(...)` | **QĐ 6 / B2** |
| 4 | `client/TuitionServiceClient.java` | Thêm `markPaid(id, transactionId)` + `getTuitionById(id)`; bọc `404` → trả `null` | **QĐ-1 + QĐ-5** — `getForObject` ném exception chứ không trả `null`, khiến `if (tuitionInfo == null)` ở `PaymentService.java:52` không bao giờ chạy |
| 5 | `service/PaymentService.java` | Bỏ `tuitionRepository` **và** `userRepository`; hiện thực saga Mục 5.4 | **QĐ-1 + QĐ-3** |
| 6 | `entity/TransactionStatus.java` | Thêm `PROCESSING` | Mục 5.6 |
| 7 | `repository/TuitionRepository.java`, `entity/Tuition.java` | **XÓA** | Không còn dùng; để lại thì Hibernate vẫn tạo bảng `paymentdb.tuitions` chết |
| 8 | `repository/UserRepository.java`, `entity/User.java` | **XÓA** | **QĐ 6** — số dư chuyển hẳn về auth-service |

**KHÔNG sửa** (theo QĐ 5-B): `dto/TuitionInfo.java`, `dto/PaymentInitResponse.java`.

> Sau thay đổi này, `paymentdb` chỉ còn **một** bảng: `transactions`. Đúng vai trò của một service điều phối.

---

## 7. Thay đổi ở `auth-service` (đã được duyệt — QĐ 6)

| # | File | Thay đổi |
|---|------|----------|
| 1 | `entity/BalanceEntry.java` | **MỚI** — sổ cái: `id, userId, transactionId, type (DEBIT/CREDIT), amount, createdAt`, `UNIQUE(transaction_id, type)` |
| 2 | `entity/EntryType.java` | **MỚI** — enum `DEBIT, CREDIT` |
| 3 | `repository/BalanceEntryRepository.java` | **MỚI** — `existsByTransactionIdAndType(...)` |
| 4 | `repository/UserRepository.java` | Thêm `@Lock(PESSIMISTIC_WRITE) findByIdForUpdate(UUID id)` |
| 5 | `dto/BalanceChangeRequest.java` / `BalanceResponse.java` | **MỚI** — `{amount, transactionId}` / `{userId, balance}` |
| 6 | `service/BalanceService.java` | **MỚI** — `debit()` / `credit()` theo mã giả Mục 5.3 |
| 7 | `controller/AuthController.java` | Thêm `POST /api/auth/users/{id}/debit` và `/credit` |
| 8 | `config/GlobalExceptionHandler.java` | **MỚI** — `404` / `409` → JSON `{message}` |

**KHÔNG sửa:** `SecurityConfig` (giữ `.anyRequest().authenticated()`), `JwtUtils`, `AuthTokenFilter`, `entity/User`.

> **Đính chính ước lượng:** lúc đặt câu hỏi tôi ước tính B2 khoảng 25 dòng. Con số thực tế là **~150 dòng**,
> vì cần sổ cái `balance_entries` để `debit`/`credit` idempotent. Không có sổ cái thì một lần retry sau timeout
> sẽ **trừ tiền hai lần** — tệ hơn chính lỗi đang sửa. Đây là phần tối thiểu để B2 đúng, không phải làm thêm cho đẹp.

---

## 8. BA-13 — Xác thực service-to-service (phát hiện mới)

**Vấn đề.** `auth-service/config/SecurityConfig.java:52` đặt `.anyRequest().authenticated()`, chỉ `permitAll` cho
`/api/auth/login` và `/api/auth/fix`. Nhưng `AuthServiceClient.getUserInfo()` gọi
`http://auth-service:8081/api/auth/users/{id}` bằng `RestTemplate` **trần, không header** → `401/403`.

→ **`initiatePayment()` đã hỏng sẵn ở dòng 61 từ trước**, độc lập hoàn toàn với vấn đề học phí. Cả plan lẫn review
BA ban đầu đều chưa bắt được. Hai endpoint `debit`/`credit` mới sẽ dính đúng lỗi này.

**Ba phương án:**

| | Cách làm | Đánh giá |
|---|---|---|
| **(a)** | `permitAll` cho `/api/auth/users/**` | ❌ **Không chọn.** `docker-compose.yml` publish port `8081` ra host → bất kỳ ai cũng gọi được `POST /debit` để trừ tiền người khác. Đây là lỗ hổng thật, không phải lý thuyết |
| **(b)** ⭐ | **Chuyển tiếp JWT của người dùng** — interceptor trên `RestTemplate` lấy header `Authorization` của request đang xử lý và gắn vào lời gọi đi | ✅ Không cần secret mới (`JWT_SECRET` đã dùng chung)<br>✅ Sửa luôn `getUserInfo()` đang lỗi<br>✅ `auth-service` giữ nguyên `SecurityConfig`<br>✅ Chỉ sửa 1 file trong phạm vi đã duyệt |
| **(c)** | Header nội bộ `X-Internal-Token` + secret riêng | ❌ Thêm biến môi trường mới → phải sửa `docker-compose.yml` (ngoài phạm vi) |

**Chọn (b).** Hiện thực trong `payment-service/config/RestTemplateConfig.java`:

```java
@Bean
public RestTemplate restTemplate() {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add((request, body, execution) -> {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String auth = attrs.getRequest().getHeader(HttpHeaders.AUTHORIZATION);
            if (auth != null && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().set(HttpHeaders.AUTHORIZATION, auth);
            }
        }
        return execution.execute(request, body);
    });
    return rt;
}
```

**Lưu ý:** interceptor gắn header cho **mọi** lời gọi, kể cả tới `tuition-service`. Vô hại — `tuition-service`
không có Spring Security nên bỏ qua header này.

**Giới hạn đã biết:** token gắn theo là token của **người dùng cuối**, không phải danh tính hệ thống. Với đồ án
là chấp nhận được. Hệ thống thật nên dùng client credentials riêng cho service-to-service.

---

## 9. Checklist tương thích

### 9.1 `TuitionServiceClient` — URL & mapping
- [ ] `GET http://tuition-service:8082/api/tuition/{mssv}` khớp `@GetMapping("/api/tuition/{mssv}")`
- [ ] JSON có đúng `id` (UUID string), `mssv` (String), `amount` (**number**, không phải string), `paid` (boolean)
- [ ] Field thừa `studentName` / `semester` không làm vỡ deserialize (`FAIL_ON_UNKNOWN_PROPERTIES` = false)
- [ ] `POST /api/tuition/{id}/mark-paid` nhận `{"transactionId":"<uuid>"}`, trả `200/404/409` đúng hợp đồng 3.5
- [ ] `404` được bọc thành `null` để nhánh `if (tuitionInfo == null)` hoạt động đúng như code đã viết

### 9.2 `AuthServiceClient` — endpoint mới
- [ ] `POST /api/auth/users/{id}/debit` trả `200` kèm số dư mới; `409` khi không đủ số dư
- [ ] `debit` gọi lại lần 2 cùng `transactionId` → `200`, **số dư không đổi** (test idempotency)
- [ ] `credit` khi chưa từng `debit` → `409` (chống hoàn tiền khống)
- [ ] Header `Authorization` được interceptor gắn tự động (Mục 8)

### 9.3 Route gateway `/api/tuition/**`
- [ ] `api-gateway/application.yml` đã có route → `http://tuition-service:8082`, `Path=/api/tuition/**` ✅ **không cần sửa**
- [ ] Controller dùng path đầy đủ `/api/tuition` — gateway **không** strip prefix

### 9.4 JWT
- [ ] **KHÔNG bắt buộc JWT trong `tuition-service`** — giữ đơn giản, không thêm `spring-boot-starter-security`
- [ ] `auth-service` **giữ nguyên** yêu cầu JWT; payment-service chuyển tiếp token (Mục 8)
- [ ] Gọi qua gateway (`:8080/api/tuition/...`) vẫn cần JWT — frontend lấy `studentName` qua đường này
- [ ] Đánh đổi đã biết: port `8082` publish ra host → truy cập tuition-service không cần auth. Chấp nhận cho đồ án

### 9.5 `docker-compose.yml` — env & role DB
- [ ] `tuition-service`: `DB_HOST=postgres`, `DB_USER=${DB_USER:-postgres}`, `DB_PASSWORD=${DB_PASSWORD:-postgres}` ✅
- [ ] `.env` có `DB_USER=postgres`, `DB_PASSWORD=postgres` → khớp `POSTGRES_USER/POSTGRES_PASSWORD` ✅
- [ ] Role `payment_user` **không tồn tại**; `.env` ghi đè thành `postgres` → chạy được (QĐ-6, không sửa)
- [ ] `payment-service` **không** `depends_on` tuition-service → có thể gọi khi chưa sẵn sàng (QĐ-7, không sửa)
- [ ] `mvn package` **trước** `docker compose build` (Dockerfile `COPY target/*.jar`) ✅

### 9.6 Database
- [ ] `tuitiondb` do `init-db.sql` tạo ✅ — **chỉ chạy lần đầu khi volume `postgres_data` còn trống** → cần `docker compose down -v`
- [ ] Sau khi xóa `entity/Tuition` + `entity/User` của payment-service, bảng `paymentdb.tuitions` / `paymentdb.users` cũ vẫn còn (Hibernate không drop). Vô hại, nên dọn bằng `down -v` để khỏi nhầm khi debug
- [ ] `authdb.balance_entries` được Hibernate tạo mới bằng `ddl-auto: update` ✅
- [ ] ⚠️ **CHECK constraint cũ trên `transactions.status`** — Hibernate 6.4 sinh
      `CHECK (status IN ('PENDING','SUCCESS','FAILED'))` lúc tạo bảng. Thêm `PROCESSING` vào enum
      **KHÔNG** cập nhật constraint đã tồn tại (`ddl-auto: update` không bao giờ sửa constraint cũ)
      → `verify-otp` lỗi `violates check constraint "transactions_status_check"`.
      Môi trường có sẵn volume phải chạy:
      ```sql
      ALTER TABLE transactions DROP CONSTRAINT transactions_status_check;
      ALTER TABLE transactions ADD CONSTRAINT transactions_status_check
          CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED'));
      ```
      Môi trường mới (`docker compose down -v`) không dính — Hibernate tạo constraint đúng 4 giá trị ngay từ đầu.
- [ ] `\gexec` là meta-command của `psql` — chạy đúng trong `docker-entrypoint-initdb.d` ✅

### 9.7 Nghiệp vụ
- [ ] MSSV `524H0088` (8 ký tự) — đã sửa regex (Mục 6 #1)
- [ ] Chuẩn hóa `UPPER()` khi tra cứu MSSV → `524h0088` và `524H0088` cùng kết quả
- [ ] Đóng hộ được phép (QĐ 4-A) → **không** kiểm tra `userId ↔ mssv`
- [ ] SV nợ nhiều kỳ → `GET` trả kỳ có `due_date` sớm nhất (QĐ 2-A)
- [ ] Sau thanh toán, **login lại phải thấy số dư đã giảm** (điểm mấu chốt của QĐ 6 / B2)

### 9.8 Demo — lấy OTP (QĐ 3-B)
- [ ] `initiate` trả `transactionId`
- [ ] Lấy OTP: `docker exec ibanking-redis redis-cli -a redis123 GET "otp:<transactionId>"`
- [ ] Nếu quên `transactionId`: `docker exec ibanking-redis redis-cli -a redis123 --scan --pattern 'otp:*'`
- [ ] OTP TTL 5 phút — hết hạn phải `initiate` lại
- [ ] Rate limit **3 lần `initiate` / giờ / user** → đừng thử quá tay khi demo

---

## 10. Vấn đề đã ghi nhận — không sửa

| Mã | Mức | Vấn đề | Vì sao không chặn |
|---|---|---|---|
| **BA-4** | 🟡 | `email_queue` khai báo lệch nhau: payment-service có `x-dead-letter-exchange`, notification-service không → `PRECONDITION_FAILED` cho service khởi động sau, thứ tự không xác định → lỗi flaky | Nằm ở `notification-service` (ngoài phạm vi). Không ảnh hưởng luồng thanh toán vì QĐ 3-B lấy OTP từ Redis |
| **BA-6** | 🟡 | Transaction `PENDING` không bao giờ hết hạn (không có `EXPIRED`/`CANCELLED`, không job dọn). Kết hợp rate limit 3 OTP/giờ → user tự khóa mình | Không chặn luồng chính. Giới hạn đã biết của đồ án |
| **BA-8** | 🟡 | `RateLimiterService` dùng `INCR` trên giá trị ghi bằng `GenericJackson2JsonRedisSerializer`. Nếu serializer bọc kiểu (`["java.lang.Integer",1]`) thì `INCR` lỗi `ERR value is not an integer` **ngay lần `initiate` thứ 2** | Chưa khẳng định — **cần test thực tế**. Nếu `initiate` lần 1 chạy mà lần 2 lỗi 400 khó hiểu, đây là nghi phạm số một |
| **BA-9** | 🟡 | FK `tuitions.mssv → students.mssv` có thể fail lúc khởi động nếu bảng đã có dòng mồ côi | `docker compose down -v` trước lần chạy đầu |
| **BA-12** | 🟢 | `paid_at` dùng `LocalDateTime`, container UTC, người dùng +07 → lệch 7 tiếng | Giữ `LocalDateTime` cho nhất quán với `payment-service` |
| **BA-11** | 🟢 | OTP dùng `Random` (không phải `SecureRandom`), `nextInt(999999)` không bao giờ sinh `999999` | Bảo mật, không chặn chức năng |
| **QĐ-4** | ⚫ | `studentName` không hiện ở `initiate` | **ĐÓNG** theo QĐ 5-B |
| **QĐ-6** | 🟢 | Role `payment_user` không tồn tại trong `init-db.sql` | `.env` có `DB_USER=postgres` che được. Cấm sửa `init-db.sql` |
| **QĐ-7** | 🟢 | `tuition-service` thiếu healthcheck | Retry là qua. Sẽ thêm `actuator` vào pom (trong phạm vi) để sau gắn healthcheck dễ |

---

## 11. Cấu trúc thư mục dự kiến

```
tuition-service/                             [MỚI HOÀN TOÀN]
├── pom.xml                                  (+ validation, + actuator)
├── Dockerfile                               (giữ nguyên)
└── src/main/
    ├── java/com/tdtu/ibanking/tuition/
    │   ├── TuitionApplication.java          (giữ nguyên)
    │   ├── config/GlobalExceptionHandler.java   404 / 409 -> JSON {message}
    │   ├── controller/TuitionController.java
    │   ├── dto/{TuitionResponse, TuitionDetailResponse, MarkPaidRequest}.java
    │   ├── entity/{Student, Tuition}.java
    │   ├── exception/{TuitionNotFound, TuitionAlreadyPaid}Exception.java
    │   ├── repository/{StudentRepository, TuitionRepository}.java
    │   └── service/TuitionService.java
    └── resources/
        ├── application.yml                  (+ sql.init.mode, defer-datasource-initialization)
        └── data.sql                         (5 SV / 7 khoản, idempotent)

payment-service/                             [SỬA]
├── config/RestTemplateConfig.java           (+ interceptor chuyển tiếp JWT)
├── client/{AuthServiceClient, TuitionServiceClient}.java   (+ debit/credit/markPaid)
├── dto/PaymentInitRequest.java              (regex MSSV)
├── entity/TransactionStatus.java            (+ PROCESSING)
├── service/PaymentService.java              (saga Mục 5.4)
└── XÓA: entity/{Tuition,User}.java, repository/{TuitionRepository,UserRepository}.java

auth-service/                                [THÊM phần số dư]
├── entity/{BalanceEntry, EntryType}.java    [MỚI]
├── repository/BalanceEntryRepository.java   [MỚI]
├── repository/UserRepository.java           (+ findByIdForUpdate)
├── dto/{BalanceChangeRequest, BalanceResponse}.java  [MỚI]
├── service/BalanceService.java              [MỚI]
├── controller/AuthController.java           (+ /debit, /credit)
└── config/GlobalExceptionHandler.java       [MỚI]
```

---

## 12. Ba cổng trước khi viết code

- [x] **(a)** `plan.md` hoàn chỉnh
- [x] **(b)** Review `/ba-skill` — đã chạy, phát hiện 3 vấn đề 🔴 mới; **tất cả đã xử lý**:
  - BA-1 (ERD tự mâu thuẫn) → bỏ partial unique index, thêm `due_date` (Mục 2.4, 2.5)
  - BA-2 (không lấy được OTP) → QĐ 3-B, đọc từ Redis (Mục 9.8)
  - BA-3 (thiếu compensation) → saga trừ-tiền-trước + đọc lại khi timeout (Mục 5.4)
  - BA-13 (xác thực service-to-service) → phát hiện sau review, chuyển tiếp JWT (Mục 8)
- [x] **(c)** Bạn xác nhận bằng lời → đã chạy `/agent-orchestrator`

## 13. Kết quả nghiệm thu (đã chạy thật ngày 30/08/2026)

**Build:** `mvn package` cả 3 service **BUILD SUCCESS** (Maven 3.9.16 + JDK 17 trong Docker —
máy dev không có `mvn`/JDK trên PATH).

**End-to-end qua gateway `:8080`:**

| # | Kiểm tra | Kết quả |
|---|----------|---------|
| E1-E2 | login `524h0088` → JWT | ✅ balance 15.000.000 |
| E3 | `initiate` MSSV `524H0088` (regex mới) | ✅ 200, amount 8.500.000 |
| E4 | Đọc OTP từ Redis (QĐ 3-B) | ✅ |
| E5 | `verify-otp` | ✅ `Payment successful` |
| V1 | `tuitions.paid=true`, `transaction_id` khớp | ✅ |
| V2 | Số dư `15.000.000 → 6.500.000` | ✅ |
| V3 | Sổ cái có 1 dòng `DEBIT` | ✅ |
| V4 | `transactions.status = SUCCESS` | ✅ |
| V5 | **Login lại hiện 6.500.000** (mục tiêu QĐ 6/B2) | ✅ |
| V6 | Thanh toán lần 2 cùng MSSV | ✅ bị từ chối |
| V8 | MSSV sai định dạng | ✅ 400 + message tiếng Việt |

**Hợp đồng `mark-paid` (Mục 3.5):** 200 · 200-idempotent · 409 khác transaction · 404 — ✅ đúng cả 4 nhánh.

**Saga bù trừ (BA-3) — mô phỏng thua race:** `debit` 5.000.000 → `mark-paid` **409** → `credit` hoàn
5.000.000 → số dư về nguyên vẹn, sổ cái lưu cả `DEBIT` + `CREDIT`, transaction `FAILED`
"Học phí đã được người khác thanh toán" — ✅.

**Ngữ nghĩa nợ nhiều kỳ (QĐ 2-A):** `524H0100` → trả HK2-2425 (nợ cũ) · `524H0789` → trả HK1-2526
(bỏ qua kỳ đã đóng) · `524h0088` chữ thường → cùng kết quả chữ hoa · `524H0123` → 404 — ✅.

**Hai lỗi phát hiện & đã sửa trong quá trình nghiệm thu:**
1. `BalanceService` bắt `DataIntegrityViolationException` quanh `save()` — vô tác dụng vì Hibernate hoãn
   INSERT tới lúc commit, và transaction lúc đó đã rollback-only. Đã chuyển nhánh replay lên
   `AuthController` + thêm `getBalance()` `@Transactional(readOnly=true)`.
2. CHECK constraint cũ trên `transactions.status` — xem Mục 9.6.
