# Đối chiếu Rubric giữa kỳ — SOA (504070)

Nguồn: `docs/rubric-midterm.pdf` (Phiếu chấm điểm giữa kỳ, HK1/2026-2027).
Đối chiếu với mã nguồn tại nhánh `tuition-service`, ngày **06/09/2026**.

**Ký hiệu:** ✅ đã hoàn thành · ⚠️ làm một phần / có rủi ro khi chấm · ❌ chưa có

**Ước lượng tổng: ~7.0 / 10** (chi tiết ở mục "Bảng tổng hợp điểm").

> **Đã kiểm chứng bằng cách chạy thật, ngày 07/09/2026** — không chỉ đọc code:
> `mvn test` xanh ở cả 3 module (**auth 10 / tuition 11 / payment 7 = 28 test, 0 failure, 0 error**, `BUILD SUCCESS` ×3),
> và `bash scripts/concurrency-test.sh` cho **PASS cả 2 kịch bản, exit code 0**. Kịch bản B lần chạy đó rơi đúng vào
> nhánh khó nhất (cả hai người cùng lọt qua `initiate`), nên **saga bù trừ đã được thực thi thật**: trừ 6.500.000
> rồi hoàn lại đủ 6.500.000, người thua giữ nguyên số dư.

---

## 1. Phân tích nghiệp vụ & UML — `…… / 1.0`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 1.1 | Use Case Diagram mô tả đúng các actor và chức năng chính (0.5) | ❌ | **Không tồn tại** trong repo. Không có file `.puml`, `.drawio`, ảnh hay khối mermaid nào. `docs/MidtermVIHK12627.md:95` chỉ nhắc lại yêu cầu của đề, chưa vẽ. |
| 1.2 | ERD xác định hợp lý entity, thuộc tính, khóa và quan hệ (0.5) | ⚠️ | Có sơ đồ **ASCII** cho `tuitiondb` và `authdb` tại `docs/plan.md:60-100` (mục 2.1, 2.2) + bảng ràng buộc (mục 2.5). **Thiếu**: `paymentdb.transactions` chưa có trong ERD; sơ đồ nằm lẫn trong nhật ký thiết kế chứ chưa phải một lược đồ ERD hoàn chỉnh để nộp. |

**Cần làm:** vẽ Use Case Diagram (actor: Sinh viên/Người nộp tiền, hệ thống Email/SMTP; use case: Đăng nhập, Xem thông tin tài khoản, Tra cứu học phí, Khởi tạo thanh toán, Xác thực OTP, Xem lịch sử giao dịch) và một ERD gộp đủ 3 database.

---

## 2. Kiến trúc Microservices — `…… / 1.5`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 2.1 | Có Microservices Architecture Diagram rõ ràng (0.5) | ⚠️ | Có sơ đồ **ASCII** trong `README.md` mục 1 (đủ 5 service, port, DB, RabbitMQ, hướng gọi). Nội dung đúng nhưng chưa phải diagram vẽ hình — nên export ra ảnh để chắc điểm (xem "Điều kiện giới hạn điểm": không có diagram → trần 8.0). |
| 2.2 | Phân rã service hợp lý, xác định trách nhiệm từng service (0.5) | ✅ | 5 service tách rõ: `api-gateway` (8080), `auth-service` (8081), `tuition-service` (8082), `payment-service` (8083), `notification-service` (8084). Bảng trách nhiệm ở `README.md` mục 1. **Data ownership rõ ràng**: mỗi service một database (`authdb`/`tuitiondb`/`paymentdb`), `payment-service` không có bảng `users`/`tuitions` cục bộ. |
| 2.3 | Giao tiếp giữa service, database/data store, external service; giải thích lựa chọn (0.5) | ✅ | **Đồng bộ (HTTP)**: `payment-service/client/AuthServiceClient.java`, `TuitionServiceClient.java`. **Bất đồng bộ (AMQP)**: RabbitMQ queue `email_queue` → `notification-service/listener/NotificationListener.java`. **Data store**: PostgreSQL ×3 DB, Redis (OTP + rate-limit + Redisson lock). **External**: SMTP Gmail (`notification-service/service/EmailService.java`). Giải thích thiết kế (saga bù trừ thay vì 2PC) ở `README.md` mục 4 + `docs/plan.md`. |

---

## 3. Thiết kế REST API — `…… / 1.0`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 3.1 | Đầy đủ API cho chức năng chính; URI + HTTP Method hợp lý (0.5) | ✅ | `POST /api/auth/login`, `GET /api/auth/users/{id}`, `POST /api/auth/users/{id}/debit\|credit`; `GET /api/tuition/{mssv}`, `/{mssv}/all`, `/id/{id}`, `POST /api/tuition/{id}/mark-paid`; `POST /api/payments/initiate`, `/verify-otp`, `GET /api/payments/history`. |
| 3.2 | Request/Response model, HTTP Status Code, validation, error handling (0.5) | ✅ | DTO riêng cho mọi request/response (`dto/` ở cả 3 service). Validation Bean Validation: `PaymentInitRequest` (`@Pattern` MSSV), `OtpVerifyRequest` (`@Pattern ^[0-9]{6}$`), `BalanceChangeRequest` (`@DecimalMin`), `MarkPaidRequest` (`@NotNull`). `@RestControllerAdvice` ở cả 3 service map đúng mã: 400 / 401 / 403 / 404 / 409 / 429 (kèm `Retry-After`) / 503 / 500. |
| — | *(ngoài rubric, cần cho mức 9.0+)* API documentation (Swagger/OpenAPI) | ✅ | ✅ *(đã bổ sung 06/09/2026)* `springdoc-openapi 2.3.0`: bản `webmvc-ui` cho `auth-service` / `tuition-service` / `payment-service`, bản `webflux-ui` cho `api-gateway`. Swagger UI **gộp** tại `http://localhost:8080/swagger-ui.html`, dropdown chọn được 3 service; bấm **Authorize** dán JWT là gọi thử API ngay trên UI. 11 endpoint đã annotate `@Tag` / `@Operation` / `@ApiResponses`, tài liệu hoá đúng các mã `GlobalExceptionHandler` thực sự trả (400 / 401 / 403 / 404 / 409 / 429 kèm header `Retry-After` / 503 / 500). Spec tĩnh nộp kèm: `docs/openapi-auth.json`, `docs/openapi-tuition.json`, `docs/openapi-payment.json`. Không mở thêm port nào ra host — chỉ gateway `:8080`. |

---

## 4. Database & Data Persistence — `…… / 1.0`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 4.1 | Database hiện thực phù hợp thiết kế, lưu đủ dữ liệu cần thiết (0.5) | ✅ | 5 bảng: `users`, `balance_entries` (authdb); `students`, `tuitions` (tuitiondb); `transactions` (paymentdb). `init-db.sql` tạo 3 DB; JPA `ddl-auto: update` sinh schema. Lịch sử giao dịch được lưu thật (`Transaction` có `status`, `errorMessage`, `createdAt`, `updatedAt`). |
| 4.2 | Constraints / data validation, dữ liệu mẫu, persistence hoạt động đúng (0.5) | ✅ | Constraints: `students.mssv UNIQUE`, `tuitions(mssv, semester) UNIQUE`, `tuitions.transaction_id UNIQUE`, `@Version` optimistic lock, `balance_entries(transaction_id, type) UNIQUE` (chốt idempotency), FK `tuitions.mssv → students.mssv`, `NOT NULL` + `precision/scale` cho tiền. Dữ liệu mẫu: `tuition-service/src/main/resources/data.sql` (5 SV, 7 khoản học phí phủ các case: nợ nhiều kỳ, đã đóng hết, số tiền vượt số dư) + `auth-service/config/DemoDataSeeder.java` (2 user). |

> ✅ *(đã sửa 06/09/2026)* Trước đây `README.md` mục 6.3 ghi các MSSV `524H0088 / 524H0100 / 524H0123 / 524H0456 / 524H0789` không tồn tại trong `data.sql` → demo theo README sẽ nhận **404**. Nay mục 6.3 liệt kê đúng `524H0001 → 524H0005` kèm số tiền và mục đích từng case, tách rõ "username đăng nhập" với "MSSV tra cứu". Comment lệch trong `DemoDataSeeder.java` và ví dụ MSSV trong `PaymentInitRequest` cũng đã cập nhật theo.

---

## 5. Hiện thực chức năng & Payment Workflow — `…… / 2.0`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 5.1 | Đăng nhập, thông tin tài khoản, tra cứu học phí (0.5) | ✅ | `AuthController.authenticateUser` (JWT + BCrypt), `GET /users/{id}` trả id/email/fullName/phone/balance kèm owner-check (`enforceOwnershipOrInternal`), `TuitionService.getUnpaidByMssv` (ưu tiên `due_date` sớm nhất). |
| 5.2 | Khởi tạo và thực hiện giao dịch, kiểm tra số dư và trạng thái học phí (0.5) | ✅ | `PaymentService.initiatePayment`: check học phí tồn tại + chưa đóng, check số dư ≥ số tiền, chặn giao dịch PENDING/PROCESSING trùng, tạo `Transaction(PENDING)`. |
| 5.3 | OTP sinh, gắn với giao dịch, có thời hạn, dùng một lần, gửi qua email (0.5) | ✅ | `SecureRandom` 6 số; key Redis `otp:{transactionId}` (gắn đúng giao dịch); TTL **5 phút**; `redisTemplate.delete()` ở **mọi** nhánh kết thúc (thành công + thất bại) ⇒ dùng một lần; gửi qua RabbitMQ → SMTP. So khớp constant-time (`MessageDigest.isEqual`). Thêm rate-limit: 3 OTP/giờ/user, 3 lần sai/giao dịch, 8 lần sai/giờ/user. |
| 5.4 | Thanh toán thành công: cập nhật số dư, gạch nợ học phí, lưu lịch sử, gửi email xác nhận (0.5) | ✅ | Saga trong `PaymentService.runSaga`: `debit` → `mark-paid` → `status = SUCCESS` → email xác nhận. Số dư: `BalanceService.debit`. Gạch nợ: `TuitionService.markPaid` (set `paid`, `paidAt`, `transactionId`). Lịch sử: bảng `transactions` + `GET /api/payments/history`. Email: `sendSuccessEmail`. |

---

## 6. Transaction & Concurrency — `…… / 1.5`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 6.1 | Giao dịch đảm bảo nhất quán khi cập nhật dữ liệu liên quan (0.5) | ✅ | `@Transactional` trong `BalanceService` / `TuitionService`. Xuyên service dùng **saga bù trừ**: `mark-paid` thất bại → `credit` hoàn tiền đầy đủ → `FAILED`. Idempotency 2 lớp: `balance_entries(transaction_id, type) UNIQUE` (không trừ/hoàn 2 lần) và `tuitions.transaction_id UNIQUE` + re-check trong `markPaid`. Retry mạng ×3, timeout thì đọc lại trạng thái thật trước khi quyết định hoàn tiền. |
| 6.2 | Nhiều giao dịch đồng thời trên cùng một tài khoản, không chi vượt số dư (0.5) | ✅ | Hai lớp: **Redisson distributed lock** `lock:account:{userId}` bao trọn `verify-otp` (`PaymentService.verifyOtpAndPay`, `tryLock(5s)`, watchdog gia hạn) + **pessimistic lock DB** `UserRepository.findByIdForUpdate` (`SELECT … FOR UPDATE`) rồi mới so `balance >= amount` trong `BalanceService.debit`. |
| 6.3 | Nhiều tài khoản cùng thanh toán một khoản học phí, chỉ 1 lần thành công (0.5) | ✅ | `TuitionRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE`) + re-check `paid` **trong lock** + `UNIQUE(transaction_id)` + `@Version`. Người thua nhận `409` → `payment-service` tự động `refundAndFail` (hoàn tiền). `initiatePayment` còn chặn sớm khi khoản học phí đang có giao dịch OTP còn hiệu lực của người khác. |
| — | *(mức 10.0)* Concurrency test / integration test tự động | ✅ | ✅ *(đã bổ sung 06/09/2026)* **28 test tự động** chạy trên Testcontainers `postgres:15-alpine` + `redis:7-alpine` (cố ý không dùng H2 vì `findFirstUnpaid` là native query và `FOR UPDATE` trên H2 cho kết quả xanh giả): `auth-service` 10, `tuition-service` 11, `payment-service` 7 — tất cả PASS, mỗi module ~10–13 giây. Hai tình huống race của đề có test riêng: `BalanceConcurrencyIT` (6.2) và `TuitionMarkPaidConcurrencyIT` (6.3), dùng *starting gate* 3 latch để bảo đảm 10 thread chạy đồng thời thật. Thêm `scripts/concurrency-test.sh` chạy E2E qua gateway `:8080` cho cả 2 kịch bản (PASS/PASS, exit 0) → **không còn phải demo tay bằng 2 terminal**. Xem README mục 7. |

---

## 7. User Interface & Integration — `…… / 0.5`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 7.1 | Giao diện hỗ trợ toàn bộ luồng: đăng nhập → tra cứu → thanh toán → OTP → kết quả/lịch sử, tích hợp thật với backend | ❌ | **Không có frontend trong repo.** Chỉ có 5 service Spring Boot (chính `README.md` cũng ghi rõ "repo này là backend thuần"). Gateway đã bật CORS sẵn cho `http://localhost:5173` (Vite) nhưng chưa có ứng dụng nào dùng. |

> Đây là mục **mất điểm chắc chắn** nếu không bổ sung, và còn kéo theo rủi ro cho mục 8.3 (demo).

---

## 8. Documentation, Deployment & Demonstration — `…… / 1.5`

| # | Yêu cầu | Trạng thái | Bằng chứng / Ghi chú |
|---|---|---|---|
| 8.1 | README/tài liệu hướng dẫn cài đặt, cấu hình, khởi chạy (0.5) | ✅ | `README.md` rất đầy đủ: kiến trúc, bảng API, luồng saga, hướng dẫn build/chạy, tài khoản demo, cách lấy OTP từ Redis, cách reset rate-limit. Kèm `docs/plan.md`, `docs/CHANGES-AUTH-PAYMENT.md`, `docs/LOI-PAYMENT-SERVICE.md`, `docs/TIEN-DO-FIX-BUG.md`. ✅ *(đã sửa 06/09/2026)* Hai điểm lệch trước đây — bảng MSSV demo và `.env.example` thiếu `INTERNAL_API_KEY` (copy file mẫu sẽ khiến mọi lời gọi service-to-service trả 403) — đều đã được vá. |
| 8.2 | Các thành phần triển khai và tích hợp chạy hoàn chỉnh; khuyến khích Docker/Docker Compose (0.5) | ✅ | `docker-compose.yml` dựng đủ Postgres (healthcheck) + Redis + RabbitMQ + 5 service, có `depends_on`, biến môi trường qua `.env`, `Dockerfile` cho từng service, chỉ expose gateway 8080 và notification 8084. |
| 8.3 | Demo thành công các chức năng chính và giải thích được kiến trúc, API, transaction/concurrency (0.5) | ⚠️ | Backend demo được bằng Postman/curl và giải thích thì tài liệu đã quá đủ. Nhưng **không có UI** nên phần "demo các chức năng chính" sẽ khó trọn vẹn; cộng thêm dữ liệu demo trong README bị lệch (mục 4). Điểm thực tế phụ thuộc buổi bảo vệ. |

---

## Bảng tổng hợp điểm

| Tiêu chí | Thang | Ước lượng | Ghi chú ngắn |
|---|---|---|---|
| 1. Phân tích nghiệp vụ & UML | 1.0 | **~0.25** | Thiếu hẳn Use Case Diagram; ERD mới ở dạng ASCII, thiếu `transactions` |
| 2. Kiến trúc Microservices | 1.5 | **~1.25** | Nội dung tốt, diagram nên vẽ thành hình |
| 3. Thiết kế REST API | 1.0 | **1.0** | Đầy đủ, validation + error handling tốt |
| 4. Database & Data Persistence | 1.0 | **1.0** | Constraints + seed data đầy đủ |
| 5. Chức năng & Payment Workflow | 2.0 | **2.0** | Toàn bộ luồng backend chạy đúng đặc tả |
| 6. Transaction & Concurrency | 1.5 | **1.5** | Xử lý đủ cả 2 tình huống race |
| 7. User Interface & Integration | 0.5 | **0** | Chưa có frontend |
| 8. Documentation, Deployment & Demo | 1.5 | **~1.25** | README + Docker tốt; demo thiếu UI |
| **TỔNG** | **10.0** | **≈ 7.0** | |

### Đối chiếu "Điều kiện giới hạn điểm"

| Trường hợp giới hạn | Có dính không? |
|---|---|
| Không có sản phẩm khởi chạy/demo được → trần 4.0 | ✅ Không dính — `docker compose up` chạy được |
| Chỉ Monolithic, service không thực sự giao tiếp → trần 6.5 | ✅ Không dính — 5 service, HTTP + AMQP thật |
| Không hiện thực OTP → trần 7.0 | ✅ Không dính — OTP đầy đủ TTL + one-time + email |
| Không có giải pháp Transaction/Concurrency → trần 7.5 | ✅ Không dính — lock + saga + idempotency |
| Không có Microservices Architecture Diagram → trần 8.0 | ⚠️ **Rủi ro** — mới có sơ đồ ASCII trong README, chưa phải diagram vẽ |

### Đối chiếu "Mức điểm mục tiêu"

- **Đạt mức 7.0–7.9:** ✅ đủ nghiệp vụ chính, service phân rã & giao tiếp, có validation/error handling.
- **Lên mức 8.0–8.9:** ✅ **demo được cả hai tình huống concurrency** (test tự động + `scripts/concurrency-test.sh`); còn lại ❌ diagram kiến trúc dạng hình.
- **Lên mức 9.0–9.5:** ✅ **API documentation (Swagger/OpenAPI)** đã có (springdoc + Swagger UI gộp ở gateway) và ✅ **automated testing** đã có (28 test, cả 3 module PASS).
- **Mức 10.0:** ✅ **integration/E2E test** và ✅ **concurrency test tự động** đã có (28 test Testcontainers + `scripts/concurrency-test.sh` chạy E2E qua gateway); ✅ Idempotency và ✅ API Gateway đã có từ trước. Chỉ còn thiếu ❌ **logging/tracing tập trung** (correlation id xuyên service).

---

## Danh sách việc còn thiếu (ưu tiên từ cao xuống thấp)

| # | Việc | Điểm ảnh hưởng | Mức độ |
|---|---|---|---|
| 1 | ❌ Vẽ **Use Case Diagram** | 0.5 (TC 1) | Bắt buộc |
| 2 | ❌ Làm **frontend** phủ luồng đăng nhập → tra cứu → thanh toán → OTP → kết quả/lịch sử (gateway đã mở CORS cho `:5173`) | 0.5 (TC 7) + cứu TC 8.3 | Bắt buộc |
| 3 | ⚠️ Hoàn thiện **ERD** (thêm `paymentdb.transactions`, xuất thành lược đồ riêng thay vì ASCII trong `plan.md`) | 0.5 (TC 1) | Bắt buộc |
| 4 | ⚠️ Xuất **Architecture Diagram** thành ảnh/draw.io | Gỡ trần 8.0 (TC 2) | Cao |
| 5 | ✅ **Đã xong** — `README.md` mục 6.3 khớp `data.sql`, `.env.example` đã có `INTERNAL_API_KEY` | TC 8.1 + tránh hỏng demo | — |
| 6 | ✅ **Đã xong** — `BalanceConcurrencyIT` + `TuitionMarkPaidConcurrencyIT` (10 thread, starting gate) và `scripts/concurrency-test.sh` chạy E2E cả 2 kịch bản qua gateway, in PASS/FAIL + exit code | Điều kiện lên 8.0+ | — |
| 7 | ✅ **Đã xong** — 28 test trên Testcontainers (auth 10 / tuition 11 / payment 7), gồm cả test saga bù trừ và rate-limit; `mvn test` xanh ở cả 3 module | Điều kiện lên 9.0+ | — |
| 8 | ✅ **Đã xong** — `springdoc-openapi 2.3.0` ở 3 service + gateway, Swagger UI gộp tại `:8080/swagger-ui.html`, spec tĩnh `docs/openapi-{auth,tuition,payment}.json` | Điều kiện lên 9.0+ | — |
| 9 | ❌ Logging/tracing tập trung (correlation id xuyên service) | Mức 10.0 | Thấp |
