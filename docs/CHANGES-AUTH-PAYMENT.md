## 0. TL;DR — Vì sao phải động vào 2 service này

Trước khi sửa, luồng thanh toán **không thể chạy được**, vì 3 lỗi nằm sẵn trong code:

```
POST /api/payments/initiate  {"mssv":"524H0088"}
  [1] @Pattern ^[0-9]{6}$              -> 400, chặn ngay tại validation
  [2] authServiceClient.getUserInfo()  -> 401/403, gọi auth-service không kèm token

POST /api/payments/verify-otp
  [3a] userRepository.findByIdForUpdate()    -> null -> NPE (paymentdb.users RỖNG)
  [3b] tuitionRepository.findByIdForUpdate() -> null -> NPE (paymentdb.tuitions RỖNG)
```

Gốc rễ của `[3a]`/`[3b]`: `payment-service` **đọc** dữ liệu qua HTTP nhưng lại **ghi** vào bảng cục bộ
của chính nó — mà **không có dòng code nào INSERT vào 2 bảng đó**. Chúng vĩnh viễn rỗng.

Hướng sửa đã chốt (plan.md Mục 0, Phương án B + QĐ 6/B2):

| Dữ liệu | Trước | Sau |
|---|---|---|
| Số dư | `paymentdb.users` (rỗng) | **`auth-service` sở hữu** — `payment-service` gọi HTTP `debit`/`credit` |
| Học phí | `paymentdb.tuitions` (rỗng) | **`tuition-service` sở hữu** — `payment-service` gọi HTTP `mark-paid` |
| `payment-service` còn giữ gì | users + tuitions + transactions | **chỉ còn `transactions`** — thuần điều phối |

---

## 1. Bản đồ thay đổi

### `auth-service` — thêm chức năng số dư (thuần additive, không phá code cũ)

| File | Loại | Nội dung |
|---|---|---|
| `pom.xml` | Sửa | + `spring-boot-starter-validation` |
| `entity/EntryType.java` | **Mới** | enum `DEBIT, CREDIT` |
| `entity/BalanceEntry.java` | **Mới** | Sổ cái + cơ chế idempotency |
| `repository/BalanceEntryRepository.java` | **Mới** | `existsByTransactionIdAndType` |
| `repository/UserRepository.java` | Sửa | + `findByIdForUpdate` (pessimistic lock) |
| `dto/BalanceChangeRequest.java` | **Mới** | `{amount, transactionId}` |
| `dto/BalanceResponse.java` | **Mới** | `{userId, balance}` |
| `exception/UserNotFoundException.java` | **Mới** | → 404 |
| `exception/InsufficientBalanceException.java` | **Mới** | → 409 |
| `exception/InvalidRefundException.java` | **Mới** | → 409 |
| `service/BalanceService.java` | **Mới** | `debit()` / `credit()` idempotent |
| `config/GlobalExceptionHandler.java` | **Mới** | Map exception → HTTP status |
| `controller/AuthController.java` | Sửa | + 2 endpoint (method cũ **không đụng**) |

**KHÔNG bị sửa:** `SecurityConfig`, `JwtUtils`, `AuthTokenFilter`, `UserDetailsImpl`,
`UserDetailsServiceImpl`, `entity/User`, `application.yml`, và 3 method cũ của `AuthController`
(`/login`, `/fix`, `GET /users/{userId}`).

### `payment-service` — chuyển từ "tự ghi DB" sang "điều phối HTTP"

| File | Loại | Nội dung |
|---|---|---|
| `dto/PaymentInitRequest.java` | Sửa | Regex MSSV |
| `entity/TransactionStatus.java` | Sửa | + `PROCESSING` |
| `config/RestTemplateConfig.java` | Sửa | + interceptor chuyển tiếp JWT |
| `client/AuthServiceClient.java` | Sửa | + `debit()`, `credit()` |
| `client/TuitionServiceClient.java` | Sửa | + `markPaid()`, `getTuitionById()`, bọc 404 |
| `service/PaymentService.java` | **Viết lại `verifyOtpAndPay`** | Saga bù trừ |
| `dto/BalanceChangeRequest.java` | **Mới** | Body gửi sang auth-service |
| `dto/BalanceResponse.java` | **Mới** | Response từ auth-service |
| `dto/TuitionDetailInfo.java` | **Mới** | Response `GET /api/tuition/id/{id}` |
| `entity/Tuition.java` | **XÓA** | — |
| `entity/User.java` | **XÓA** | — |
| `repository/TuitionRepository.java` | **XÓA** | — |
| `repository/UserRepository.java` | **XÓA** | — |

**KHÔNG bị sửa:** `dto/TuitionInfo.java`, `dto/PaymentInitResponse.java` (quyết định QĐ 5-B),
`GlobalExceptionHandler`, `RedisConfig`, `RedissonConfig`, `RabbitMQConfig`, `SecurityConfig`,
`JwtAuthenticationFilter`, `JwtUtil`, `RateLimiterService`, `PaymentController`, `pom.xml`.

---

## 2. `auth-service` — chi tiết từng thay đổi

### 2.1 `repository/UserRepository.java` — thêm pessimistic lock

**Trước**
```java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Boolean existsByUsername(String username);
}
```

**Sau**
```java
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByUsername(String username);
    Boolean existsByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)                       // -> SELECT ... FOR UPDATE
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
```

**Vì sao:** `auth-service` giờ là nơi **duy nhất** trừ/cộng tiền. Hai request cùng lúc trên cùng một
tài khoản phải bị tuần tự hóa ở tầng DB, nếu không sẽ lost-update (đọc cùng số dư cũ, ghi đè lẫn nhau).
`SELECT ... FOR UPDATE` là điểm đồng bộ duy nhất giữa các service.

---

### 2.2 `entity/BalanceEntry.java` — sổ cái, **trái tim của idempotency**

**Trước:** không tồn tại.

**Sau** (rút gọn phần quan trọng)
```java
@Entity
@Table(
        name = "balance_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"transaction_id", "type"})  // <-- CHỐT
)
public class BalanceEntry {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "user_id", nullable = false)        private UUID userId;
    @Column(name = "transaction_id", nullable = false) private UUID transactionId;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8) private EntryType type;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(name = "created_at", updatable = false)     private LocalDateTime createdAt;

    // constructor 4 tham số để service gọi gọn
    public BalanceEntry(UUID userId, UUID transactionId, EntryType type, BigDecimal amount) { ... }
}
```

**Vì sao — đây là phần dễ hiểu nhầm nhất, đọc kỹ:**

Saga ở `payment-service` **có retry**. Nếu `debit` bị timeout, `payment-service` gọi lại. Không có gì
chặn thì lần gọi thứ hai sẽ **trừ tiền lần nữa** — tệ hơn chính lỗi ban đầu đang sửa.

`UNIQUE (transaction_id, type)` giải quyết triệt để:
- Mỗi `transactionId` chỉ ghi được **đúng 1 dòng `DEBIT`** → không bao giờ trừ 2 lần.
- Vẫn cho phép thêm **1 dòng `CREDIT`** cho cùng `transactionId` → hoàn tiền được đúng 1 lần.
- Bảng này đồng thời là **audit log dòng tiền** — bắt buộc với nghiệp vụ tiền bạc.

> Lúc thiết kế đã ước lượng phần này "~25 dòng". Thực tế ~150 dòng, chủ yếu là sổ cái.
> Đây **không phải** làm thêm cho đẹp — bỏ sổ cái đi thì `debit`/`credit` mất tính idempotent
> và toàn bộ cơ chế retry của saga trở thành nguồn gây thất thoát tiền.

---

### 2.3 `service/BalanceService.java` — `debit()` / `credit()`

**Trước:** không tồn tại (số dư chỉ được **đọc** qua `GET /api/auth/users/{id}`, không có đường ghi).

**Sau**
```java
@Transactional
public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
    if (balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT)) {
        return current(userId);                  // (A) replay -> 200, KHÔNG trừ lần nữa
    }
    User user = userRepository.findByIdForUpdate(userId)   // (B) SELECT ... FOR UPDATE
            .orElseThrow(() -> new UserNotFoundException(userId));

    if (user.getBalance().compareTo(amount) < 0) {
        throw new InsufficientBalanceException();          // (C) -> 409
    }
    user.setBalance(user.getBalance().subtract(amount));
    userRepository.save(user);

    balanceEntryRepository.save(new BalanceEntry(userId, transactionId, EntryType.DEBIT, amount));
    return toResponse(user);
}
```

`credit()` giống hệt, thêm một chốt chặn:
```java
if (!balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT)) {
    throw new InvalidRefundException();   // chống hoàn tiền khống: chưa từng trừ thì không được hoàn
}
```

**Ba tầng bảo vệ, đừng bỏ tầng nào:**
1. `(A)` — chặn replay ở tầng ứng dụng (nhanh, xử lý 99% trường hợp retry).
2. `(B)` — tuần tự hóa 2 request khác `transactionId` trên cùng tài khoản.
3. `UNIQUE` ở DB — chặn 2 request **cùng** `transactionId` chạy song song, cùng vượt qua `(A)`
   trước khi ai kịp ghi sổ. Xem [Mục 4.1](#41-nhánh-chống-race-viết-sai-đã-sửa) để hiểu vì sao
   nhánh này được xử lý ở controller chứ không ở đây.

---

### 2.4 `controller/AuthController.java` — thêm 2 endpoint

**Trước:** chỉ có `GET /fix`, `POST /login`, `GET /users/{userId}`.

**Sau** (3 method cũ giữ nguyên từng ký tự, chỉ thêm phần dưới)
```java
@PostMapping("/users/{id}/debit")
public ResponseEntity<BalanceResponse> debit(@PathVariable UUID id,
                                             @Valid @RequestBody BalanceChangeRequest request) {
    try {
        return ResponseEntity.ok(
                balanceService.debit(id, request.getAmount(), request.getTransactionId()));
    } catch (DataIntegrityViolationException e) {
        // UNIQUE(transaction_id, type) đã chặn: một request song song cùng
        // transactionId vừa ghi sổ cái trước. Coi như replay -> trả số dư hiện tại.
        return ResponseEntity.ok(balanceService.getBalance(id));
    }
}
// credit() y hệt cấu trúc
```

**Vì sao `try/catch` nằm ở controller mà không nằm trong service:** xem [Mục 4.1](#41-nhánh-chống-race-viết-sai-đã-sửa).

---

### 2.5 `config/GlobalExceptionHandler.java` — cẩn thận chỗ này

**Sau**
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(UserNotFoundException.class)         -> 404
    @ExceptionHandler(InsufficientBalanceException.class)  -> 409
    @ExceptionHandler(InvalidRefundException.class)        -> 409
    @ExceptionHandler(MethodArgumentNotValidException.class) -> 400
}
```

**⚠️ Cố ý KHÔNG bắt `RuntimeException` chung chung.** `AuthController.getUserInfo()` đang ném
`RuntimeException("User not found")` và được xử lý theo cách khác. Nếu thêm handler cho
`RuntimeException`, hành vi sẵn có của endpoint đó sẽ đổi ngầm. Nếu sau này bạn muốn thêm,
hãy kiểm tra lại `getUserInfo()` trước.

---

## 3. `payment-service` — chi tiết từng thay đổi

### 3.1 `dto/PaymentInitRequest.java` — regex MSSV

**Trước**
```java
@Pattern(regexp = "^[0-9]{6}$", message = "MSSV phải là 6 chữ số")
```

**Sau**
```java
@Pattern(regexp = "^[0-9]{3}[A-Za-z][0-9]{4}$", message = "MSSV không đúng định dạng (VD: 524H0088)")
```

**Vì sao:** MSSV thật của TDTU là `524H0088` — 8 ký tự, có chữ cái. Regex cũ khiến request bị
`MethodArgumentNotValidException` chặn ở tầng validation, **trả 400 trước khi kịp gọi tuition-service**.
Không một request hợp lệ nào tới được service kia.

---

### 3.2 `config/RestTemplateConfig.java` — chuyển tiếp JWT

**Trước**
```java
@Bean
public RestTemplate restTemplate() {
    return new RestTemplate();     // trần, không header
}
```

**Sau**
```java
@Bean
public RestTemplate restTemplate() {
    RestTemplate rt = new RestTemplate();
    rt.getInterceptors().add((request, body, execution) -> {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {                                   // guard: có thể gọi ngoài ngữ cảnh request
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

**Vì sao — đây là lỗi có sẵn từ trước, không phải phát sinh do đợt sửa này:**

`auth-service/SecurityConfig` đặt `.anyRequest().authenticated()`, chỉ `permitAll` cho `/login` và `/fix`.
Nhưng `AuthServiceClient` gọi `http://auth-service:8081/api/auth/users/{id}` bằng `RestTemplate` trần,
**không có header nào** → `401/403`. Nghĩa là `initiatePayment()` đã hỏng sẵn ở dòng gọi `getUserInfo()`,
độc lập hoàn toàn với vấn đề học phí. Hai endpoint `debit`/`credit` mới cũng sẽ dính y hệt.

**Vì sao chọn cách này thay vì `permitAll`:** `docker-compose.yml` publish port `8081` ra host.
Nếu `permitAll` cho `/api/auth/users/**` thì **bất kỳ ai cũng gọi được `POST /debit` để trừ tiền
người khác**. Đó là lỗ hổng thật, không phải lý thuyết.

**Giới hạn đã biết:** token được chuyển tiếp là token của **người dùng cuối**, không phải danh tính hệ
thống. Chấp nhận được cho đồ án; hệ thống thật nên dùng client credentials riêng cho service-to-service.

---

### 3.3 `client/TuitionServiceClient.java`

**Trước**
```java
public TuitionInfo getTuitionByMssv(String mssv) {
    String url = TUITION_SERVICE_URL + "/api/tuition/" + mssv;
    return restTemplate.getForObject(url, TuitionInfo.class);
}
```

**Sau** — 3 method, mỗi cái xử lý lỗi theo một cách **cố ý khác nhau**:

```java
// 404 -> null, VÌ PaymentService có sẵn nhánh `if (tuitionInfo == null)`
public TuitionInfo getTuitionByMssv(String mssv) {
    try { return restTemplate.getForObject(url, TuitionInfo.class); }
    catch (HttpClientErrorException.NotFound e) { return null; }
}

// 404 -> null. Dùng ở bước "đọc lại trạng thái thật" khi mark-paid timeout
public TuitionDetailInfo getTuitionById(UUID id) { ... }

// KHÔNG nuốt lỗi — để 409/404 ném ra ngoài cho saga phân biệt nhánh
public TuitionDetailInfo markPaid(UUID tuitionId, UUID transactionId) {
    return restTemplate.postForObject(url, new MarkPaidRequest(transactionId), TuitionDetailInfo.class);
}
```

**Vì sao `getTuitionByMssv` phải bọc 404 thành `null`:** `RestTemplate.getForObject` **ném**
`HttpClientErrorException` khi gặp 404, **không trả `null`**. Nhánh `if (tuitionInfo == null)` viết sẵn
trong `PaymentService` vì thế không bao giờ chạy, và người dùng nhận message kỹ thuật kiểu
`404 Not Found: "{...}"` thay vì câu tiếng Việt dễ hiểu.

**Vì sao `markPaid` thì ngược lại, cố tình KHÔNG bọc:** saga cần phân biệt rạch ròi
`409` (người khác đã đóng → phải hoàn tiền) với `404` (không thấy khoản học phí) với
timeout (chưa rõ → phải đọc lại). Nuốt hết thành `null` là mất thông tin, không quyết định đúng được.

---

### 3.4 `dto/TuitionDetailInfo.java` — vì sao phải tạo DTO mới

**Vì sao không dùng lại `TuitionInfo`:** `TuitionInfo` **không được phép sửa** (quyết định QĐ 5-B),
mà nó thiếu `transactionId` — chính là field saga cần để trả lời câu hỏi *"khoản này có phải do
chính giao dịch của mình đóng không?"* ở nhánh timeout. Nên tách DTO riêng.

Field thừa trong JSON (`studentName`, `semester`, `dueDate`, `paidAt`…) bị bỏ qua an toàn vì
Spring Boot mặc định tắt `FAIL_ON_UNKNOWN_PROPERTIES`.

---

### 3.5 `entity/TransactionStatus.java`

**Trước** `PENDING, SUCCESS, FAILED` → **Sau** `PENDING, PROCESSING, SUCCESS, FAILED`

**Vì sao cần `PROCESSING`:** trước đây trừ tiền và đánh dấu học phí nằm trong **cùng một transaction DB**
— hoặc cả hai xong, hoặc cả hai rollback, không có trạng thái ở giữa. Giờ chúng là **hai lời gọi mạng
tới hai service khác nhau**, nên tồn tại khoảng thời gian *"đã trừ tiền nhưng chưa biết học phí thế nào"*.
`PROCESSING` đánh dấu đúng khoảng đó.

**Quy ước vận hành:** `PROCESSING` là trạng thái **duy nhất cần con người can thiệp**. Nó chỉ xuất hiện
khi một service chết hẳn giữa chừng. Cần đối soát tay.

> ⚠️ **Bẫy khi deploy lên môi trường đã có sẵn dữ liệu:** Hibernate sinh
> `CHECK (status IN ('PENDING','SUCCESS','FAILED'))` lúc tạo bảng. Thêm giá trị vào enum **KHÔNG**
> cập nhật constraint cũ — `ddl-auto: update` không bao giờ sửa constraint. `verify-otp` sẽ chết với
> `violates check constraint "transactions_status_check"`. Chạy:
> ```sql
> ALTER TABLE transactions DROP CONSTRAINT transactions_status_check;
> ALTER TABLE transactions ADD CONSTRAINT transactions_status_check
>     CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED'));
> ```
> Môi trường mới (`docker compose down -v`) không dính lỗi này.

---

### 3.6 `service/PaymentService.java` — phần thay đổi lớn nhất

#### `initiatePayment()` — logic giữ nguyên, chỉ đổi message

Duy nhất: các message lỗi đổi từ tiếng Anh (`"Tuition not found for MSSV: "`, `"Insufficient balance"`)
sang tiếng Việt. Luồng không đổi.

#### `verifyOtpAndPay()` — viết lại hoàn toàn

**Trước** — một `@Transactional` bao trọn, ghi thẳng vào DB cục bộ:
```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public String verifyOtpAndPay(UUID transactionId, String otp, UUID userId) {
    ... kiểm OTP ...
    RLock accountLock = redissonClient.getLock("lock:account:" + transaction.getUserId());
    RLock tuitionLock = redissonClient.getLock("lock:tuition:" + transaction.getTuitionId());
    ...
    User user       = userRepository.findByIdForUpdate(transaction.getUserId());      // -> null, NPE
    Tuition tuition = tuitionRepository.findByIdForUpdate(transaction.getTuitionId()); // -> null, NPE

    user.setBalance(user.getBalance().subtract(transaction.getAmount()));
    userRepository.save(user);
    tuition.setPaid(true);
    tuitionRepository.save(tuition);
    transaction.setStatus(TransactionStatus.SUCCESS);
    ...
}
```

**Sau** — không còn `@Transactional` ở method, tách thành saga có bù trừ:

```
verifyOtpAndPay()
 ├─ kiểm OTP + rate limit           (giữ nguyên logic cũ)
 ├─ nếu status == SUCCESS -> trả luôn (chống double-submit)
 ├─ chiếm lock:account:{userId}     (lớp bảo vệ phụ)
 └─ runSaga()
      ├─ [2] status = PROCESSING, save        <-- ghi ý định TRƯỚC mọi lời gọi mạng
      ├─ [3] doDebit()          -> auth-service
      ├─ [4] doMarkPaidWithSaga() -> tuition-service
      └─ [5] dọn OTP + rate limit, gửi email
```

**Nhánh xử lý ở bước [4]** (`doMarkPaidWithSaga`):

| Kết quả `markPaid` | Hành động |
|---|---|
| `200` | `status = SUCCESS` |
| `409` người khác đã đóng | **`credit()` hoàn tiền** → `FAILED` |
| `404` không thấy khoản học phí | **`credit()` hoàn tiền** → `FAILED` |
| timeout/5xx | retry ×2, rồi **đọc lại** `getTuitionById()`: |
| ↳ `transactionId` trùng của mình | thực ra đã thành công → `SUCCESS` |
| ↳ `paid == false` | request chưa tới nơi → **hoàn tiền** → `FAILED` |
| ↳ `paid == true` nhưng `transactionId` khác | người khác đóng lúc mình retry → **hoàn tiền** → `FAILED` |
| ↳ không đọc được luôn | giữ `PROCESSING`, log ERROR, đối soát tay |

**Ba quyết định thiết kế cần hiểu, đừng vô tình đảo ngược khi refactor:**

**(1) Trừ tiền TRƯỚC, đánh dấu học phí SAU.** Không phải ngẫu nhiên. Nếu làm ngược lại, khi bước sau
hỏng thì thao tác bù trừ là "gỡ `paid` qua mạng" — mà lời gọi mạng đó **cũng có thể fail**, để lại học
phí đã đóng mà không ai trả tiền. Đặt trừ tiền trước thì bù trừ là hoàn tiền — thao tác **xác minh được**
bằng cách đọc lại sổ cái.

**(2) Bước "đọc lại trạng thái thật" khi timeout là bắt buộc.** Nó phân biệt *"request chưa tới nơi"*
với *"request đã commit nhưng mất response"*. Thiếu bước này, mọi timeout đều dẫn tới hoàn tiền — kể cả
khi học phí thực sự đã được đóng thành công.

**(3) Không có `@Transactional` bao trọn method.** Method này gọi HTTP ra ngoài. Giữ một transaction DB
mở suốt lời gọi mạng sẽ giam connection và làm cạn pool khi service kia chậm. Mỗi lần
`transactionRepository.save(...)` đã tự mở một transaction ngắn riêng của Spring Data JPA — đủ dùng.

**Về Redisson lock:**
- `lock:tuition:{id}` — **BỎ**. `payment-service` không còn sở hữu dữ liệu học phí; lock này không
  bảo vệ được gì. Việc chống race giờ do `tuition-service.markPaid()` làm bằng `SELECT ... FOR UPDATE`
  + re-check `paid` bên trong lock.
- `lock:account:{userId}` — **GIỮ**, nhưng chỉ còn là lớp bảo vệ phụ chống double-click của cùng một
  user. Nguồn sự thật là `SELECT ... FOR UPDATE` ở `auth-service`. Lock được giữ xuyên suốt saga
  (kể cả qua lời gọi mạng) — chấp nhận được vì đây là Redis lock, không phải JDBC connection.

**Nhánh hoàn tiền cũng thất bại** (`refundAndFail`): nếu `credit()` hết lượt retry mà vẫn timeout →
**giữ nguyên `PROCESSING`**, cố ý **không** set `FAILED`. Vì lúc đó tiền đã bị trừ nhưng chưa chắc đã
hoàn; đánh dấu `FAILED` sẽ xóa mất dấu vết cần đối soát.

---

### 3.7 Bốn file bị XÓA

| File | Vì sao xóa |
|---|---|
| `entity/User.java` | Số dư chuyển hẳn về `auth-service`. Giữ lại thì Hibernate vẫn tạo bảng `paymentdb.users` chết, gây nhầm lẫn khi debug |
| `repository/UserRepository.java` | Không còn nơi dùng |
| `entity/Tuition.java` | Học phí chuyển hẳn về `tuition-service`. Tương tự, giữ lại sẽ tạo bảng `paymentdb.tuitions` chết |
| `repository/TuitionRepository.java` | Không còn nơi dùng |

Sau khi xóa, `paymentdb` **chỉ còn một bảng: `transactions`** — đúng vai trò của service điều phối.

> Bảng `paymentdb.users` / `paymentdb.tuitions` cũ **vẫn còn trong DB** (Hibernate không bao giờ drop
> bảng). Vô hại, nhưng nên `docker compose down -v` để tránh nhầm khi debug.

---

## 4. Hai lỗi phát hiện khi chạy thật (build không bắt được)

### 4.1 Nhánh chống race viết sai (đã sửa)

**Code sai ban đầu** — nằm trong `BalanceService.debit()`:
```java
try {
    balanceEntryRepository.save(new BalanceEntry(...));
} catch (DataIntegrityViolationException e) {
    return current(userId);            // KHÔNG BAO GIỜ CHẠY TỚI ĐÂY
}
```

**Vì sao sai — hai lý do độc lập, cái nào cũng đủ làm hỏng:**

1. `BalanceEntry.id` dùng `GenerationType.UUID` → Hibernate sinh id trong bộ nhớ, nên **hoãn `INSERT`
   tới lúc commit**. `DataIntegrityViolationException` nổ **sau khi** đã ra khỏi method, `catch` không
   bắt được.
2. Kể cả bắt được, transaction lúc đó đã bị đánh dấu **rollback-only**. Câu `SELECT` trong
   `current(userId)` sẽ hỏng tiếp với `UnexpectedRollbackException`.

Kết quả thực tế: **HTTP 500** thay vì 200-idempotent như thiết kế.

**Đã sửa:** bỏ `try/catch` khỏi service, để exception thoát ra ngoài proxy (lúc đó transaction đã
rollback sạch), rồi bắt ở `AuthController` — nơi gọi `balanceService.getBalance(id)` **qua proxy**
nên mở được transaction mới. Đó là lý do `getBalance()` tồn tại:

```java
@Transactional(readOnly = true)
public BalanceResponse getBalance(UUID userId) { return current(userId); }
```

> **Bài học khi refactor:** đừng chuyển `try/catch` này ngược vào service. Gọi
> `this.getBalance()` từ trong service là **self-invocation**, bỏ qua proxy → `@Transactional`
> không có tác dụng → lỗi quay lại y như cũ.

### 4.2 CHECK constraint cũ trên `transactions.status`

Xem [Mục 3.5](#35-entitytransactionstatusjava). Chỉ ảnh hưởng môi trường đã có sẵn volume Postgres.

---

## 5. Đọc lại code theo thứ tự nào

Muốn hiểu luồng một lần cho xong, đọc đúng thứ tự này:

```
1. payment-service/dto/PaymentInitRequest.java      <- điểm vào, validation
2. payment-service/service/PaymentService.java
      initiatePayment()                             <- tra cứu + tạo transaction + gửi OTP
3. payment-service/config/RestTemplateConfig.java   <- vì sao lời gọi ra ngoài có token
4. payment-service/service/PaymentService.java
      verifyOtpAndPay() -> runSaga()                <- XƯƠNG SỐNG, đọc kỹ nhất
      ├─ doDebit()
      ├─ doMarkPaidWithSaga()
      └─ refundAndFail()
5. payment-service/client/*.java                    <- cách mỗi lỗi HTTP được xử lý khác nhau
6. auth-service/service/BalanceService.java         <- đầu kia của debit/credit
7. auth-service/entity/BalanceEntry.java            <- vì sao idempotent được
8. auth-service/controller/AuthController.java      <- vì sao try/catch nằm ở đây (Mục 4.1)
```

---

## 6. Chạy lại & kiểm thử

**Build** (máy dev không có `mvn`/JDK trên PATH — build qua Docker):
```powershell
docker volume create ibanking-m2
docker run --rm -v "C:\VS Code\SOA\Midtern-Backend:/repo" -v ibanking-m2:/root/.m2 `
  -w /repo/auth-service maven:3.9-eclipse-temurin-17 mvn -B package -DskipTests
# lặp lại với payment-service, tuition-service
```

**Deploy:**
```powershell
docker compose up -d --build auth-service tuition-service payment-service
```

**Luồng end-to-end** (OTP lấy từ Redis theo QĐ 3-B, vì `EMAIL_USER`/`EMAIL_PASS` trong `.env` để trống):
```bash
G=http://localhost:8080
curl -s $G/api/auth/fix                                   # tạo/reset user demo
TOKEN=$(curl -s -X POST $G/api/auth/login -H "Content-Type: application/json" \
  -d '{"username":"524h0088","password":"123456"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')

TXID=$(curl -s -X POST $G/api/payments/initiate -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{"mssv":"524H0088"}' \
  | sed -n 's/.*"transactionId":"\([^"]*\)".*/\1/p')

OTP=$(docker exec ibanking-redis redis-cli -a redis123 --no-auth-warning GET "otp:$TXID" | tr -d '"\r')

curl -s -X POST $G/api/payments/verify-otp -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d "{\"transactionId\":\"$TXID\",\"otp\":\"$OTP\"}"
# -> Payment successful
```

**Kiểm chứng sau giao dịch:**
```powershell
docker exec postgres psql -U postgres -d tuitiondb -c "SELECT mssv, paid, transaction_id FROM tuitions WHERE mssv='524H0088';"
docker exec postgres psql -U postgres -d authdb    -c "SELECT username, balance FROM users WHERE username='524h0088';"
docker exec postgres psql -U postgres -d authdb    -c "SELECT type, amount, transaction_id FROM balance_entries;"
docker exec postgres psql -U postgres -d paymentdb -c "SELECT status, error_message FROM transactions;"
```

**Reset về trạng thái seed để demo lại:**
```powershell
docker exec postgres psql -U postgres -d tuitiondb -c "UPDATE tuitions SET paid=false, paid_at=NULL, transaction_id=NULL WHERE id IN ('22222222-2222-2222-2222-222222222001','22222222-2222-2222-2222-222222222002','22222222-2222-2222-2222-222222222003');"
docker exec postgres psql -U postgres -d authdb    -c "DELETE FROM balance_entries; UPDATE users SET balance=15000000.00 WHERE username='524h0088';"
docker exec postgres psql -U postgres -d paymentdb -c "DELETE FROM transactions;"
docker exec ibanking-redis redis-cli -a redis123 --no-auth-warning FLUSHDB
```

> **Lưu ý khi test lặp:** rate limit là **3 lần `initiate` / giờ / user**. Hết lượt thì xóa key:
> `docker exec ibanking-redis redis-cli -a redis123 --no-auth-warning DEL "otp:request:<userId>"`

---

## 7. Những gì vẫn còn tồn tại (biết trước để khỏi mất công truy)

| Vấn đề | Ảnh hưởng |
|---|---|
| `email_queue` khai báo lệch giữa `payment-service` (có DLX) và `notification-service` (không) → `PRECONDITION_FAILED` cho service khởi động sau | Email không tới nơi. Không chặn thanh toán vì OTP lấy từ Redis |
| Transaction `PENDING` không bao giờ hết hạn (thiếu `EXPIRED`/`CANCELLED`, không job dọn) | Rác dữ liệu, user có thể tự khóa mình bằng rate limit |
| `RateLimiterService` dùng `INCR` trên giá trị ghi bằng `GenericJackson2JsonRedisSerializer` | Nếu `initiate` lần 1 chạy mà lần 2 lỗi 400 khó hiểu → đây là nghi phạm số một |
| OTP dùng `Random` (không phải `SecureRandom`), `nextInt(999999)` không bao giờ sinh `999999` | Bảo mật, không chặn chức năng |
| `paid_at` dùng `LocalDateTime`, container chạy UTC | Hiển thị lệch 7 tiếng so với giờ VN |

Chi tiết đầy đủ ở [`plan.md`](./plan.md) Mục 10.
