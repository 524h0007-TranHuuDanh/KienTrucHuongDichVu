# Recap tiến độ fix bug — dán file này vào Claude để tiếp tục

> **Cách dùng:** mở phiên Claude Code mới trong thư mục dự án, dán nguyên nội dung file này vào, rồi nói *"tiếp tục từ chỗ đang làm dở"*.

---

## 0. Cách tôi muốn làm việc (đọc phần này trước)

- **Hướng dẫn tôi tự gõ code, đừng viết hộ.** Mục tiêu của tôi là hiểu tuition-service, không phải có code chạy được.
- Cách hướng dẫn tôi thấy hiệu quả: giải thích *tại sao* trước → đưa **khung sườn có chỗ trống + TODO** → chỉ rõ các cái bẫy → tôi tự điền → dán lại cho Claude soi → sửa → mới đi bước tiếp.
- Soi code của tôi thì **liệt kê lỗi ra để tôi tự sửa**, đừng sửa hộ.
- Mỗi lần chỉ đi **một bước**, xong mới sang bước sau.
- Trả lời bằng **tiếng Việt**, hạn chế thuật ngữ, thuật ngữ nào bắt buộc dùng thì giải thích ngắn ở lần đầu.

---

## 1. Bối cảnh dự án

Hệ thống iBanking đóng học phí, kiến trúc microservice, bài giữa kỳ môn SOA.

| Thành phần | Cổng | Vai trò |
|---|---|---|
| `api-gateway` | 8080 | Spring Cloud Gateway (WebFlux), kiểm JWT bằng `GlobalFilter`, chỉ cổng này mở ra host |
| `auth-service` | 8081 | Đăng nhập, phát JWT, quản lý số dư (`debit`/`credit` có bảng ghi sổ chống trùng) |
| `tuition-service` | 8082 | Tra cứu học phí, đánh dấu đã đóng |
| `payment-service` | 8083 | Điều phối thanh toán: OTP → trừ tiền → báo học phí → hoàn tiền nếu hỏng |
| `notification-service` | 8084 | Nghe RabbitMQ, gửi email |

- Spring Boot **3.2.4**, Java 17, PostgreSQL, Redis, RabbitMQ, Docker Compose.
- Git branch hiện tại: `tuition-service`.
- Xác thực có **hai loại**: người dùng cuối dùng **JWT**; các service gọi nhau dùng **khoá nội bộ** ở header `X-Internal-Api-Key`.

### Hai tài liệu lỗi đã có trong repo

| File | Nội dung |
|---|---|
| `LOI-PAYMENT-SERVICE.md` | 24 lỗi của payment-service (P-01 → P-24), đầy đủ nguyên nhân / vị trí / cách sửa. **Chưa fix cái nào.** |
| `TIEN-DO-FIX-BUG.md` | File này — lỗi tuition-service + tiến độ |

---

## 2. Đã làm xong

### ✅ Đóng cổng ra host (nửa đầu của T-01)

Đã xoá khối `ports:` của `auth-service`, `tuition-service`, `payment-service` trong `docker-compose.yml`. Giờ chỉ `api-gateway:8080` (và `notification:8084`, `postgres`, `redis`, `rabbitmq`) mở ra host.

**Hệ quả cần nhớ:** không `curl localhost:8082` được nữa. Muốn test trực tiếp phải gọi từ trong mạng Docker:

```bash
docker compose exec payment-service curl -i http://tuition-service:8082/api/tuition/524H0088
```

### ✅ B1 — Thêm thư viện vào `tuition-service/pom.xml`

`spring-boot-starter-security` + `jjwt-api` / `jjwt-impl` / `jjwt-jackson` (0.11.5).

### ✅ B2 — Khai báo khoá bí mật JWT

- `tuition-service/src/main/resources/application.yml`: thêm `jwt.secret: ${JWT_SECRET}`
- `docker-compose.yml`: thêm `JWT_SECRET: ${JWT_SECRET}` vào `environment` của `tuition-service`

### ✅ B3 — Viết `tuition-service/.../tuition/util/JwtUtil.java`

Có 4 hàm: `getSignKey()`, `extractAllClaims()`, `getUserIdFromToken()`, `validateToken()`. Đã xử lý xong các điểm: `StandardCharsets.UTF_8`, `parseClaimsJws` (có chữ `s`), import đúng `io.jsonwebtoken.security.SignatureException`, thứ tự `catch` từ hẹp tới rộng, nhánh cuối bắt `Exception` (không phải `JwtException`) để không sót `IllegalArgumentException` khi token rỗng.

> 🔸 Còn sót: `import lombok.RequiredArgsConstructor;` không dùng tới, và thụt lề toàn file lệch 2 dấu cách. Dọn lúc nào cũng được.

---

## 3. ĐANG LÀM DỞ — T-01, bước B4

### Lộ trình đang đi (Hướng B: thêm Spring Security đầy đủ)

| Bước | Việc | File | Trạng thái |
|------|------|------|-----------|
| B1 | Thêm thư viện | `pom.xml` | ✅ |
| B2 | Khoá bí mật JWT | `application.yml` + `docker-compose.yml` | ✅ |
| B3 | Lớp đọc & kiểm token | `util/JwtUtil.java` | ✅ |
| **B4** | **Viết lại `InternalApiKeyFilter`** | `config/InternalApiKeyFilter.java` | **⬅️ ĐANG Ở ĐÂY, chưa gõ dòng nào** |
| B5 | Viết filter JWT | `filter/JwtAuthenticationFilter.java` *(chưa có thư mục `filter/`)* | ⬜ |
| B6 | Viết `SecurityConfig` lắp 2 filter | `config/SecurityConfig.java` *(chưa có)* | ⬜ |
| B7 | Test 4 API + kiểm luồng thanh toán còn sống | curl | ⬜ |

### ⚠️ Lý thuyết nền — phải nắm trước khi làm B4

**Vấn đề:** tuition-service có **hai loại khách** với hai cách chứng minh danh tính khác nhau:

| Khách | Gọi API | Chứng minh bằng |
|---|---|---|
| Người dùng cuối (qua gateway) | 3 API `GET` tra cứu | JWT ở header `Authorization` |
| payment-service | `POST /api/tuition/{id}/mark-paid` | khoá ở header `X-Internal-Api-Key` |

**Cái bẫy:** Spring Security tự đăng ký ở order rất nhỏ (≈ `Integer.MIN_VALUE + 9900`), chạy **trước** `InternalApiKeyFilter` hiện tại (order = 1). Nếu `SecurityConfig` viết `.anyRequest().authenticated()` thì request `mark-paid` (chỉ có khoá nội bộ, không có JWT) sẽ bị đá ra **401 trước khi `InternalApiKeyFilter` kịp chạy** → payment-service gãy → thanh toán trừ tiền rồi hoàn lại → toàn bộ chức năng chết. Log sẽ chỉ báo "hệ thống đang bận", không nhắc gì tới Spring Security.

**Chìa khoá:** `.authenticated()` **không** nghĩa là "phải có JWT hợp lệ". Nó nghĩa là **"phải có một object `Authentication` nằm trong `SecurityContextHolder`"** — một cái hộp gắn với luồng đang xử lý request. Ai bỏ vào cũng được, bằng cách nào cũng được, miễn bỏ trước khi `AuthorizationFilter` mở hộp.

**Cách giải:** kéo `InternalApiKeyFilter` vào **bên trong** dãy Spring Security, đặt **trước** filter JWT, và cho nó bỏ danh tính vào hộp khi khoá đúng:

```
Request → [Spring Security]
            ├─ InternalApiKeyFilter    → khoá đúng?  → bỏ "internal-service" vào hộp
            ├─ JwtAuthenticationFilter → token đúng? → bỏ userId vào hộp
            └─ AuthorizationFilter     → mở hộp: có gì → vào Controller; rỗng → 401
```

**File mẫu để tham khảo (auth-service đã giải xong bài này):**
- `auth-service/src/main/java/com/tdtu/ibanking/auth/security/InternalApiKeyFilter.java`
- `auth-service/src/main/java/com/tdtu/ibanking/auth/config/SecurityConfig.java` — chú ý 2 dòng `addFilterBefore`

### Việc cụ thể của B4

File: `tuition-service/src/main/java/com/tdtu/ibanking/tuition/config/InternalApiKeyFilter.java`

Hiện tại lớp ngoài tên `InternalApiKeyFilter` nhưng **không phải filter** — nó là lớp `@Configuration` chứa lớp con `MarkPaidGuardFilter` mới là filter thật.

| | Hiện tại | Cần thành |
|---|---|---|
| Lớp ngoài | `@Configuration`, không phải filter | `@Component`, **chính nó là filter** |
| Kiểu filter | `implements Filter` | `extends OncePerRequestFilter` |
| Đăng ký ở đâu | `FilterRegistrationBean`, order 1, dãy servlet toàn cục | **bỏ hẳn** — B6 nhét vào dãy Security |
| Khi khoá đúng | cho đi tiếp, không làm gì thêm | **đặt danh tính vào `SecurityContextHolder`** |
| Khi khoá sai | trả 403 | giữ nguyên |

Phần đọc header / so khoá bằng `constantTimeEquals` / trả 403 — **chép nguyên từ `MarkPaidGuardFilter` cũ**, code đó đang đúng. Rồi xoá lớp con và phương thức `internalApiKeyFilterRegistration()` đi.

**Vì sao đổi sang `OncePerRequestFilter`:** (1) không phải ép kiểu `ServletRequest` → `HttpServletRequest`; (2) bảo đảm chạy đúng một lần dù request bị chuyển tiếp nội bộ; (3) `addFilterBefore/After` của Spring Security làm việc trơn với nó.

**Dòng quan trọng nhất của cả bước B4:**

```java
SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("internal-service", null, Collections.emptyList()));
```

**⚠️ Cái bẫy chết người ở dòng này:** `UsernamePasswordAuthenticationToken` có 2 hàm khởi tạo.
- Bản **2 tham số** `(principal, credentials)` → tạo danh tính **CHƯA xác thực** (`isAuthenticated() == false`) → `AuthorizationFilter` vẫn trả **401**, code nhìn hợp lý mà cứ 401, không log gì.
- Bản **3 tham số** `(principal, credentials, authorities)` → danh tính **ĐÃ xác thực**. ✅

Luôn truyền đủ 3 tham số, kể cả khi quyền rỗng — đó là lý do tham số thứ ba là `Collections.emptyList()` chứ không phải `null`.

**💡 Gộp T-06 vào luôn (khuyến nghị, tốn ~2 dòng):** đổi `private static final String PROTECTED_PATTERN` thành `private static final List<String> PROTECTED_PATTERNS` rồi kiểm bằng `.stream().anyMatch(p -> pathMatcher.match(p, path))` — giống auth-service. Xong luôn lỗi T-06.

### Ghi chú cho B6 (đừng quên)

Vì `InternalApiKeyFilter` và `JwtAuthenticationFilter` đều là bean kiểu `Filter`, **Spring Boot sẽ tự đăng ký chúng vào dãy servlet toàn cục** — cộng thêm việc mình `addFilterBefore(...)` vào dãy Security nữa là **đăng ký hai chỗ**. auth-service và payment-service đang dính lỗi này (chạy đúng nhờ `OncePerRequestFilter` tự chặn, nhưng là dựa vào may). Cách vá sạch, thêm vào `SecurityConfig`:

```java
@Bean
public FilterRegistrationBean<InternalApiKeyFilter> disableAutoRegistration(InternalApiKeyFilter filter) {
    FilterRegistrationBean<InternalApiKeyFilter> reg = new FilterRegistrationBean<>(filter);
    reg.setEnabled(false);   // chỉ tắt đăng ký toàn cục, không ảnh hưởng dãy Security
    return reg;
}
```

### Kế hoạch test ở B7

| Trường hợp | Mong đợi |
|---|---|
| `GET /api/tuition/524H0088` qua gateway 8080, **không** token | 401 |
| `GET /api/tuition/524H0088` qua gateway 8080, **có** token hợp lệ | 200 |
| `GET` gọi thẳng `localhost:8082` | Connection refused (cổng đã đóng) |
| `GET` gọi từ trong mạng Docker, không token | 401 (trước khi fix là 200) |
| `POST .../mark-paid` **không** có khoá nội bộ | 403 |
| Chạy hết luồng thanh toán thật (initiate → verify-otp) | **Thành công** — đây là phép thử quan trọng nhất, nó chứng minh cái bẫy ở trên đã được xử lý |

---

## 4. Lỗi tuition-service còn lại

### ✅ T-02 — Mọi lỗi bất ngờ đều thành mã 400 `[Trung bình]` — ĐÃ FIX

**Vị trí:** `tuition-service/src/main/java/com/tdtu/ibanking/tuition/config/GlobalExceptionHandler.java`

Trước: nhánh `@ExceptionHandler(RuntimeException.class)` bắt **mọi lỗi lúc chạy** rồi trả 400, kèm `ex.getMessage()` lộ ra ngoài. Lỗi tranh chấp ghi đồng thời (`ObjectOptimisticLockingFailureException`) cũng ra 400 → payment-service thử lại 3 lần rồi treo giao dịch ở `PROCESSING` (nguyên nhân gián tiếp của P-05).

**Đã làm:**
- Thêm `@Slf4j`, dùng `errorBody(...)` cho nhất quán với các handler cũ.
- Thêm `@ExceptionHandler(ObjectOptimisticLockingFailureException.class)` → **409** + message thân thiện, log ở mức WARN.
- Sửa nhánh `RuntimeException` → **500** + message cố định "Có lỗi xảy ra, vui lòng thử lại sau", log ở mức ERROR kèm stack trace. Không còn lộ `ex.getMessage()` ra body.

> Vì `markPaid` đã có khoá bi quan (`findByIdForUpdate`), request thứ 2 thường đọc được `paid=true` → đi nhánh `TuitionAlreadyPaidException` (409). Handler optimistic-lock là lưới an toàn. → T-03 (bỏ `@Version`) giờ là tuỳ chọn.

### T-03 — Thừa một lớp chống tranh chấp `[Nhẹ]`

**Vị trí:** `entity/Tuition.java` (cột `@Version`) + `repository/TuitionRepository.java` (`findByIdForUpdate` với `PESSIMISTIC_WRITE`)

`markPaid` dùng cùng lúc khoá bi quan (khoá dòng dữ liệu) và khoá lạc quan (cột `@Version`). Khoá bi quan đã đủ; cột phiên bản chỉ thêm khả năng sinh lỗi tranh chấp, mà lỗi đó lại bị quy về 400 (T-02).

**Sửa:** giữ khoá bi quan, bỏ `@Version`. Hoặc giữ cả hai nhưng phải sửa T-02 trước.

### ✅ T-04 — Dữ liệu mẫu không đặt lại được `[Nhẹ nhưng ảnh hưởng demo]` — ĐÃ FIX

**Vị trí:** `tuition-service/src/main/resources/data.sql` + `application.yml` (`spring.sql.init.mode: always`)

Trước: mọi câu `ON CONFLICT (id) DO NOTHING` → sau lần test đầu, học phí `524H0088` đã `paid = true` và không bao giờ về lại; muốn reset phải xoá volume Postgres (mất luôn dữ liệu auth + payment).

**Đã làm:** đổi cả 5 khối `INSERT INTO tuitions` sang `ON CONFLICT (id) DO UPDATE SET <cột> = EXCLUDED.<cột>` (đủ `paid`, `paid_at`, `transaction_id`, `version`). Mỗi lần container khởi động, các dòng học phí ghi đè về đúng trạng thái seed. Khối `students` cũng chuyển `DO UPDATE` cho nhất quán (vô hại). Không còn cần `docker compose down -v` để test lại.

### T-05 — Không lưu ai đóng và đóng bao nhiêu `[Nhẹ]`

**Vị trí:** `service/TuitionService.java` hàm `markPaid`, `entity/Tuition.java`

`markPaid` chỉ nhận `transactionId`. Không lưu số tiền thực nhận, không lưu người đóng. Nếu học phí bị sửa giá giữa lúc bấm thanh toán và lúc nhập OTP, hệ thống đánh dấu đã đóng đủ trong khi thực tế người dùng trả theo giá cũ — **không có dấu vết nào để đối chiếu về sau**.

**Sửa:** thêm 2 cột (số tiền thực nhận, mã người đóng), payment-service gửi kèm khi gọi `mark-paid`.

### T-06 — Bộ lọc khoá nội bộ viết cứng một đường dẫn `[Nhẹ]`

**Vị trí:** `config/InternalApiKeyFilter.java`, hằng số `PROTECTED_PATTERN`

Là một hằng số chuỗi đơn, chỉ chặn POST. Sau này thêm API nội bộ khác (ví dụ API huỷ đánh dấu đã đóng phục vụ đối soát ở P-14) mà quên thêm vào đây thì API đó mở toang, không cảnh báo gì.

**Sửa:** đổi sang `List<String>` nhiều mẫu đường dẫn như auth-service. → **Gộp vào B4 luôn cho tiện.**

### T-07 — Truy vấn dùng cú pháp riêng PostgreSQL `[Rất nhẹ]`

**Vị trí:** `repository/TuitionRepository.java`, hàm `findFirstUnpaid`

Viết SQL thuần với `LIMIT` — chỉ chạy trên PostgreSQL/MySQL, không chạy trên Oracle/SQL Server.

**Sửa:** dùng truy vấn theo tên phương thức của Spring Data: `findFirstByStudentMssvAndPaidFalseOrderByDueDateAsc`.

---

## 5. Lỗi ngoài tuition-service cần xử

### A-01 — `/api/auth/fix` công khai `[RẤT NGHIÊM TRỌNG — auth-service]`

**Vị trí:** `auth-service/.../controller/AuthController.java` hàm `fixPassword()`

`GET /api/auth/fix` được `permitAll` ở **cả** gateway lẫn auth-service. Mở trình duyệt gõ đường dẫn đó là mật khẩu tài khoản `524h0088` bị đặt lại thành `123456`, hoặc tài khoản được tạo mới với số dư 15 triệu. Không cần đăng nhập gì cả.

Đây là code viết tạm lúc dev. Để nguyên khi nộp bài/demo thì ai xem màn hình cũng chiếm được tài khoản.

**Sửa:** xoá hẳn phương thức, và xoá đường dẫn khỏi danh sách permitAll ở **3 chỗ**:
1. `api-gateway/.../filter/JwtAuthenticationFilter.java` → `PUBLIC_PATHS`
2. `api-gateway/.../config/SecurityConfig.java` → `pathMatchers(...)`
3. `auth-service/.../config/SecurityConfig.java` → `requestMatchers(...)`

Tài khoản mẫu chuyển sang file `data.sql` như tuition-service đang làm.

### payment-service — 24 lỗi, xem `LOI-PAYMENT-SERVICE.md`

Chưa fix cái nào. Thứ tự ưu tiên đã ghi cuối file đó:

- **Bắt buộc trước khi demo:** P-01 (nhập lại OTP cũ sau khi thất bại thì đóng học phí miễn phí), P-02 (hai request cùng lúc), P-06 (in token ra log), P-07 (xung đột khai báo hàng đợi email giữa payment và notification)
- **Nên sửa:** P-03, P-04, P-05, P-08, P-09, P-11, P-12, P-15
- **Nếu còn thời gian:** phần còn lại

---

## 6. Thứ tự đề xuất cho hôm sau

1. ~~**Xong T-01** (B4 → B7)~~ — thư mục `filter/` + `SecurityConfig.java` đã có (cần kiểm lại B7)
2. ~~**T-02**~~ ✅ đã fix
3. ~~**T-04**~~ ✅ đã fix
4. **A-01** — ⬅️ ĐANG LÀM — 5 phút, xoá code, mà mức độ nghiêm trọng nhất toàn dự án
5. Sang `LOI-PAYMENT-SERVICE.md`, bắt đầu từ **P-01**

---

## 7. Những chỗ ĐÃ ĐÚNG — đừng sửa nhầm

- **Bảng ghi sổ chống trừ tiền hai lần ở auth-service** (`BalanceEntry` + `existsByTransactionIdAndType`) — ý tưởng đúng, chỉ cần bổ sung phần xét đã hoàn tiền hay chưa (xem P-01).
- **Cơ chế `markPaid` idempotent ở tuition-service** — cùng `transactionId` thì trả kết quả cũ, khác thì báo 409. Đúng.
- **Nhánh đối chiếu sau khi thử lại hết lượt** trong `doMarkPaidWithSaga` của payment-service — đọc lại trạng thái học phí rồi so `transactionId`. Đây là phần thiết kế tốt nhất của cả dự án.
- **Kiểm `isHeldByCurrentThread()` trước khi nhả khoá Redisson** — tránh nhả nhầm khoá luồng khác.
- **So khoá nội bộ bằng `MessageDigest.isEqual`** (chống đo thời gian) ở tuition-service và auth-service.
- **Khoá số tiền tại thời điểm khởi tạo giao dịch** thay vì đọc lại lúc xác nhận OTP — đúng về nghiệp vụ.
