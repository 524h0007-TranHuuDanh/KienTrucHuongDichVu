# API cho Frontend (iBanking Demo)

Base URL (qua API Gateway): `http://localhost:8080`

Frontend gọi **tất cả API qua gateway ở cổng 8080**, không gọi thẳng vào từng service (auth :8081 / tuition :8082 / payment :8083 không mở CORS và không dành cho gọi trực tiếp từ trình duyệt).

CORS đã được bật cho origin `http://localhost:5173`. Nếu frontend chạy ở origin khác, báo lại để cập nhật.

## Xác thực

- Sau khi login thành công, lưu lại `accessToken` và gửi kèm mọi request tiếp theo (trừ `/login`) bằng header:
  ```
  Authorization: Bearer <accessToken>
  ```
- Token hết hạn / sai / thiếu → **401 Unauthorized**.
- Không có API refresh token hay logout — hết hạn thì phải login lại.

## Định dạng lỗi

Hầu hết lỗi nghiệp vụ trả về JSON dạng:
```json
{ "message": "Nội dung lỗi bằng tiếng Việt" }
```
Riêng lỗi sai mật khẩu khi login trả về:
```json
{ "error": "Sai mật khẩu!" }
```
Riêng lỗi validate ở `POST /api/payments/verify-otp`/`initiate` có thể trả về map theo tên field:
```json
{ "mssv": "MSSV không đúng định dạng (VD: 524H0088)" }
```

Các mã HTTP thường gặp: `400` (dữ liệu/nghiệp vụ không hợp lệ), `401` (chưa đăng nhập/token sai), `403` (không có quyền), `404` (không tìm thấy), `409` (xung đột — hết hạn mức, đã thanh toán rồi...).

---

## 1. Auth Service — `/api/auth`

### POST `/api/auth/login`
Đăng nhập. **Không cần token.**

Request:
```json
{ "username": "524h0088", "password": "123456" }
```
Response `200`:
```json
{
  "accessToken": "eyJhbGciOi...",
  "userId": "b3a1...-uuid",
  "email": "524h0088@tdtu.edu.vn",
  "balance": 100000000
}
```
Response `401` (sai user/pass):
```json
{ "error": "Sai mật khẩu!" }
```

### GET `/api/auth/users/{userId}`
Lấy thông tin + số dư của **chính người dùng đang đăng nhập** (`userId` phải trùng với user trong token, khác sẽ bị `403`).

Headers: `Authorization: Bearer <token>`

Response `200`:
```json
{
  "id": "b3a1...-uuid",
  "email": "524h0088@tdtu.edu.vn",
  "balance": 91500000
}
```

> Các endpoint `/api/auth/users/{id}/debit` và `/api/auth/users/{id}/credit` chỉ dùng nội bộ giữa các service (yêu cầu API key riêng), **frontend không gọi**.

---

## 2. Tuition Service — `/api/tuition`

Tất cả endpoint dưới đây yêu cầu `Authorization: Bearer <token>`.

### GET `/api/tuition/{mssv}`
Lấy khoản học phí **chưa đóng, đến hạn sớm nhất** của 1 MSSV.

Response `200`:
```json
{
  "id": "22222222-...-uuid",
  "mssv": "524H0088",
  "studentName": "Tran Huu Danh",
  "semester": "HK1-2526",
  "amount": 8500000,
  "paid": false
}
```
Response `404`: không còn khoản học phí nào chưa đóng cho MSSV này.

### GET `/api/tuition/{mssv}/all`
Lấy **toàn bộ** học phí (đã đóng + chưa đóng) của 1 MSSV — dùng cho màn lịch sử học phí.

Response `200`:
```json
[
  {
    "id": "22222222-...-uuid",
    "mssv": "524H0100",
    "studentName": "Nguyen Thanh Quy",
    "semester": "HK2-2425",
    "amount": 5000000,
    "paid": false
  },
  {
    "id": "22222222-...-uuid2",
    "mssv": "524H0100",
    "studentName": "Nguyen Thanh Quy",
    "semester": "HK1-2526",
    "amount": 9200000,
    "paid": false
  }
]
```

### GET `/api/tuition/id/{id}`
Chi tiết 1 khoản học phí theo `id` (UUID) — có thêm `dueDate`, `paidAt`, `transactionId`.

Response `200`:
```json
{
  "id": "22222222-...-uuid",
  "mssv": "524H0088",
  "studentName": "Tran Huu Danh",
  "semester": "HK1-2526",
  "amount": 8500000,
  "paid": false,
  "dueDate": "2025-10-15",
  "paidAt": null,
  "transactionId": null
}
```

> `POST /api/tuition/{id}/mark-paid` chỉ dùng nội bộ (payment-service tự gọi), **frontend không gọi**.

---

## 3. Payment Service — `/api/payments`

Tất cả endpoint dưới đây yêu cầu `Authorization: Bearer <token>`.

### POST `/api/payments/initiate`
Bắt đầu thanh toán học phí cho 1 MSSV. Hệ thống kiểm tra học phí chưa đóng + số dư đủ, tạo giao dịch `PENDING`, gửi mã OTP 6 số về email của **người đang đăng nhập** (hiệu lực 5 phút).

Request:
```json
{ "mssv": "524H0088" }
```
> `mssv` phải đúng định dạng `3 số + 1 chữ + 4 số`, VD `524H0088`. Có thể đóng hộ MSSV khác với tài khoản MSSV mình.

Response `200`:
```json
{
  "transactionId": "44444444-...-uuid",
  "amount": 8500000,
  "balance": 100000000
}
```
Lỗi thường gặp: `404` không tìm thấy khoản chưa đóng, `409`/`400` số dư không đủ hoặc đã đóng rồi, `429`-kiểu (400 với message) nếu xin OTP quá nhiều lần trong thời gian ngắn.

### POST `/api/payments/verify-otp`
Xác nhận OTP để hoàn tất thanh toán (trừ tiền + đánh dấu học phí đã đóng + gửi email xác nhận).

Request:
```json
{ "transactionId": "44444444-...-uuid", "otp": "123456" }
```
Response `200` (trả về chuỗi thuần, không phải JSON object):
```
"Payment successful"
```
Lỗi thường gặp: `400` OTP sai/hết hạn, thử quá số lần cho phép, giao dịch không tồn tại hoặc không thuộc về user hiện tại.

---

## Tài khoản & dữ liệu demo có sẵn

| Username | Password | Số dư | Ghi chú |
|---|---|---|---|
| `524h0088` | `123456` | 100.000.000 | Học phí HK1-2526 = 8.500.000 chưa đóng → demo thanh toán thành công |
| `524h0456` | `123456` | 15.000.000 | Học phí = 20.000.000 chưa đóng → demo lỗi thiếu số dư |

MSSV khác để tra cứu (không có tài khoản đăng nhập riêng, chỉ dùng để test GET/initiate hộ):
- `524H0123` — đã đóng hết học phí → test case "không còn khoản chưa đóng" (`404`)
- `524H0100`, `524H0789` — nợ nhiều học kỳ → test `/all` trả nhiều dòng, `/{mssv}` trả đúng kỳ sớm nhất

Email OTP dùng tài khoản Gmail thật đã cấu hình sẵn ở backend — OTP sẽ được gửi vào hộp thư của user đang đăng nhập khi gọi `initiate`.

---

## Chưa có (nếu frontend cần, báo lại để bổ sung backend)

- Đăng ký tài khoản mới (hiện chỉ có 2 tài khoản demo seed sẵn ở trên)
- Lịch sử giao dịch thanh toán (list các payment đã thực hiện của user)
- Refresh token / logout
