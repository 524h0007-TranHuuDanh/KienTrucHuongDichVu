# Danh sách lỗi — payment-service

Tài liệu này liệt kê các lỗi tìm được trong `payment-service`. Mỗi lỗi gồm 4 phần: tên lỗi, nguyên nhân, vị trí trong code, và cách sửa.

Mức độ chia thành: **Nghiêm trọng** (mất tiền / sai nghiệp vụ), **Cao** (dễ gây hỏng khi vận hành thật), **Trung bình** (sai logic nhỏ, sai chuẩn), **Nhẹ** (dọn dẹp).

---

## MỤC LỤC

| Mã | Tên lỗi | Mức độ |
|----|---------|--------|
| [P-01](#p-01-nhập-lại-otp-cũ-sau-khi-giao-dịch-thất-bại-thì-đóng-được-học-phí-mà-không-mất-tiền) | Nhập lại OTP cũ sau khi giao dịch thất bại thì đóng được học phí mà không mất tiền | Nghiêm trọng |
| [P-02](#p-02-hai-request-cùng-lúc-đều-chạy-được-quy-trình-thanh-toán) | Hai request cùng lúc đều chạy được quy trình thanh toán | Nghiêm trọng |
| [P-03](#p-03-gọi-sang-service-khác-không-đặt-giới-hạn-thời-gian-chờ) | Gọi sang service khác không đặt giới hạn thời gian chờ | Cao |
| [P-04](#p-04-khoá-tài-khoản-chỉ-giữ-được-30-giây-ngắn-hơn-thời-gian-xử-lý) | Khoá tài khoản chỉ giữ được 30 giây, ngắn hơn thời gian xử lý | Cao |
| [P-05](#p-05-thử-lại-một-cách-mù-quáng-với-những-lỗi-mà-thử-lại-cũng-vô-ích) | Thử lại một cách mù quáng với những lỗi mà thử lại cũng vô ích | Cao |
| [P-06](#p-06-in-toàn-bộ-mã-đăng-nhập-của-người-dùng-ra-log) | In toàn bộ mã đăng nhập của người dùng ra log | Cao |
| [P-07](#p-07-hai-service-khai-báo-cùng-một-hàng-đợi-email-theo-hai-kiểu-khác-nhau) | Hai service khai báo cùng một hàng đợi email theo hai kiểu khác nhau | Cao |
| [P-08](#p-08-mã-otp-dễ-đoán-và-thiếu-mất-một-giá-trị) | Mã OTP dễ đoán và thiếu mất một giá trị | Trung bình |
| [P-09](#p-09-đếm-số-lần-nhập-sai-otp-bị-lệch-một-đơn-vị) | Đếm số lần nhập sai OTP bị lệch một đơn vị | Trung bình |
| [P-10](#p-10-bộ-đếm-giới-hạn-không-an-toàn-khi-nhiều-người-gọi-cùng-lúc) | Bộ đếm giới hạn không an toàn khi nhiều người gọi cùng lúc | Trung bình |
| [P-11](#p-11-mọi-lỗi-đều-trả-về-cùng-một-mã-400) | Mọi lỗi đều trả về cùng một mã 400 | Trung bình |
| [P-12](#p-12-trường-otp-để-trống-vẫn-lọt-qua-vòng-kiểm-tra-dữ-liệu) | Trường OTP để trống vẫn lọt qua vòng kiểm tra dữ liệu | Trung bình |
| [P-13](#p-13-cấu-hình-redis-trong-file-cấu-hình-không-có-tác-dụng) | Cấu hình Redis trong file cấu hình không có tác dụng | Trung bình |
| [P-14](#p-14-không-có-cách-nào-tra-cứu-lại-giao-dịch-bị-treo) | Không có cách nào tra cứu lại giao dịch bị treo | Trung bình |
| [P-15](#p-15-gửi-email-thất-bại-làm-hỏng-cả-giao-dịch-đã-thành-công) | Gửi email thất bại làm hỏng cả giao dịch đã thành công | Trung bình |
| [P-16](#p-16-có-thể-lỗi-khi-service-học-phí-trả-về-giá-trị-trống) | Có thể lỗi khi service học phí trả về giá trị trống | Nhẹ |
| [P-17](#p-17-tạo-nhiều-giao-dịch-chờ-cho-cùng-một-khoản-học-phí) | Tạo nhiều giao dịch chờ cho cùng một khoản học phí | Nhẹ |
| [P-18](#p-18-bị-trừ-lượt-gửi-otp-dù-chưa-hề-gửi-otp-nào) | Bị trừ lượt gửi OTP dù chưa hề gửi OTP nào | Nhẹ |
| [P-19](#p-19-tạo-giao-dịch-mới-là-xoá-sạch-số-lần-nhập-sai-otp) | Tạo giao dịch mới là xoá sạch số lần nhập sai OTP | Nhẹ |
| [P-20](#p-20-một-api-trả-về-chữ-thường-trong-khi-các-api-khác-trả-về-json) | Một API trả về chữ thường trong khi các API khác trả về JSON | Nhẹ |
| [P-21](#p-21-so-sánh-otp-theo-cách-có-thể-bị-đo-thời-gian-và-otp-lưu-dạng-thô) | So sánh OTP theo cách có thể bị đo thời gian, và OTP lưu dạng thô | Nhẹ |
| [P-22](#p-22-mã-đăng-nhập-của-người-dùng-bị-gắn-kèm-vào-mọi-lời-gọi-đi-ra-ngoài) | Mã đăng nhập của người dùng bị gắn kèm vào mọi lời gọi đi ra ngoài | Nhẹ |
| [P-23](#p-23-mở-đường-cho-một-chức-năng-không-hề-được-cài-đặt) | Mở đường cho một chức năng không hề được cài đặt | Nhẹ |
| [P-24](#p-24-thông-tin-kết-nối-cơ-sở-dữ-liệu-mặc-định-không-dùng-được) | Thông tin kết nối cơ sở dữ liệu mặc định không dùng được | Nhẹ |

---

## P-01. Nhập lại OTP cũ sau khi giao dịch thất bại thì đóng được học phí mà không mất tiền

**Mức độ:** Nghiêm trọng

### Nguyên nhân

Quy trình thanh toán gồm ba bước: trừ tiền → báo cho hệ thống học phí là đã đóng → nếu bước hai hỏng thì hoàn tiền lại. Bước hoàn tiền chạy đúng, nhưng sau khi hoàn tiền xong hệ thống **không dọn dẹp**: mã OTP vẫn nằm nguyên trong bộ nhớ tạm và còn hiệu lực thêm 5 phút, còn giao dịch chỉ bị đánh dấu là "thất bại" chứ không bị khoá lại.

Hàm xử lý OTP chỉ chặn đường khi giao dịch đã ở trạng thái "thành công". Giao dịch ở trạng thái "thất bại" thì vẫn được cho chạy lại từ đầu.

Trong khi đó bên auth-service có một cơ chế chống trừ tiền hai lần: nó ghi nhớ rằng "giao dịch X đã từng bị trừ tiền rồi", và nếu ai đó yêu cầu trừ tiền lần nữa với cùng mã giao dịch X thì nó bỏ qua, không trừ thêm. Cơ chế này bản thân nó đúng, nhưng nó chỉ nhớ *đã từng trừ*, chứ không biết rằng *tiền đó đã được hoàn lại rồi*.

Ghép hai điều trên lại thì xảy ra kịch bản sau:

1. Người dùng nhập OTP. Hệ thống trừ 8.500.000đ, ghi nhận "giao dịch X đã trừ tiền".
2. Bước báo sang hệ thống học phí bị lỗi mạng. Hệ thống hoàn 8.500.000đ về, đánh dấu giao dịch X là thất bại. **Số dư trở lại như cũ.**
3. Trong vòng 5 phút, người dùng bấm xác nhận lại với đúng mã giao dịch X và đúng mã OTP cũ. Cả hai đều còn hiệu lực nên đi qua được.
4. Hệ thống gọi trừ tiền lần nữa. auth-service thấy "giao dịch X đã trừ rồi" nên **không trừ gì cả**, chỉ trả về số dư hiện tại. Hệ thống hiểu nhầm là trừ thành công.
5. Lần này mạng đã ổn, hệ thống báo sang học phí thành công. Khoản học phí được đánh dấu **đã đóng**.

Kết quả: học phí ghi nhận đã đóng, số dư của người dùng không thay đổi một đồng nào. Đây là lỗ hổng cho phép đóng học phí miễn phí, và người dùng bình thường cũng có thể vô tình kích hoạt nó chỉ bằng cách bấm "thử lại" khi thấy báo lỗi.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/service/PaymentService.java:87` — hàm `verifyOtpAndPay`, chỗ chỉ kiểm tra trạng thái `SUCCESS`
- `payment-service/.../service/PaymentService.java:150` — lệnh xoá OTP nằm trong nhánh thành công, nên nhánh thất bại không bao giờ chạy tới
- `payment-service/.../service/PaymentService.java:239` — hàm `refundAndFail`, sau khi hoàn tiền không xoá OTP

### Cách sửa

Có ba việc cần làm cùng lúc:

1. **Chỉ cho chạy quy trình thanh toán khi giao dịch đang ở trạng thái "chờ".** Nếu giao dịch đã ở trạng thái thất bại thì trả về thông báo yêu cầu tạo giao dịch mới, không chạy lại. Nếu đang ở trạng thái "đang xử lý" thì báo cho người dùng chờ.
2. **Xoá mã OTP và bộ đếm số lần nhập sai ở mọi nhánh kết thúc**, không riêng nhánh thành công. Cách gọn nhất là đưa hai lệnh dọn dẹp này vào khối `finally` bao quanh toàn bộ quy trình, hoặc gọi chúng ngay bên trong `failTransaction`.
3. **Sửa cơ chế chống trừ tiền hai lần bên auth-service** để nó xét cả việc đã hoàn tiền hay chưa. Thay vì hỏi "giao dịch X đã từng bị trừ chưa", nên hỏi "giao dịch X hiện đang ở trạng thái đã trừ và chưa hoàn, đúng không". Nếu đã có cả bản ghi trừ lẫn bản ghi hoàn thì phải coi như giao dịch đã kết thúc và từ chối, chứ không được trả về "thành công" một cách im lặng.

Việc 1 và 2 nằm trong payment-service và là bắt buộc. Việc 3 nằm ở auth-service nhưng nên làm để có hai lớp bảo vệ.

---

## P-02. Hai request cùng lúc đều chạy được quy trình thanh toán

**Mức độ:** Nghiêm trọng

### Nguyên nhân

Trong hàm xử lý OTP, thứ tự các bước đang là:

1. Đọc giao dịch từ cơ sở dữ liệu
2. Kiểm tra giao dịch đã thành công chưa
3. Kiểm tra mã OTP có đúng không
4. **Rồi mới** giành khoá tài khoản
5. Chạy quy trình thanh toán

Vấn đề nằm ở chỗ ba bước kiểm tra đầu diễn ra **trước khi** giành khoá. Nếu người dùng bấm nút hai lần thật nhanh, hai request sẽ cùng đi qua bước 1, 2, 3 (lúc đó giao dịch vẫn đang ở trạng thái chờ, OTP vẫn đúng), rồi mới xếp hàng ở bước 4.

Request thứ nhất giành được khoá, chạy xong, đặt trạng thái thành công. Request thứ hai sau đó giành được khoá — nhưng nó vẫn đang cầm bản sao giao dịch đọc từ bước 1, tức là **bản cũ với trạng thái "chờ"**. Nó không đọc lại từ cơ sở dữ liệu nên không hề biết giao dịch vừa thành công. Nó cứ thế chạy tiếp: đặt trạng thái về "đang xử lý" (ghi đè lên "thành công"), gọi trừ tiền lần nữa, gọi báo học phí lần nữa, và **gửi email xác nhận thanh toán lần thứ hai**.

May mắn là tiền không bị trừ hai lần, nhờ cơ chế chống trùng bên auth-service. Nhưng trạng thái giao dịch bị ghi đè sai và người dùng nhận hai email — đủ để gây hoang mang và khiếu nại.

Một chi tiết làm vấn đề khó phát hiện hơn: bảng giao dịch không có cột đánh dấu phiên bản, nên khi hai luồng cùng ghi thì luồng nào ghi sau sẽ đè lên luồng trước mà không có cảnh báo nào.

### Vị trí

- `payment-service/.../service/PaymentService.java:87-127` — hàm `verifyOtpAndPay`, thứ tự kiểm tra và giành khoá
- `payment-service/.../service/PaymentService.java:129` — hàm `runSaga` nhận vào đối tượng giao dịch đã đọc từ trước
- `payment-service/src/main/java/com/tdtu/ibanking/payment/entity/Transaction.java` — thiếu cột đánh dấu phiên bản

### Cách sửa

1. **Đọc lại giao dịch từ cơ sở dữ liệu ngay sau khi giành được khoá**, rồi mới kiểm tra trạng thái lần nữa. Nếu lúc này thấy đã thành công thì trả về luôn kết quả thành công, không chạy lại gì cả.
2. Thêm cột đánh dấu phiên bản vào bảng giao dịch (trong JPA là chú thích `@Version`). Khi đó nếu vẫn còn trường hợp hai luồng cùng ghi, hệ thống sẽ báo lỗi rõ ràng thay vì âm thầm ghi đè.
3. Nếu muốn chặt chẽ hơn nữa, đưa cả bước kiểm tra OTP vào bên trong vùng khoá.

---

## P-03. Gọi sang service khác không đặt giới hạn thời gian chờ

**Mức độ:** Cao

### Nguyên nhân

Đối tượng dùng để gọi HTTP sang các service khác được tạo bằng lệnh mặc định, không kèm bất kỳ cấu hình nào về thời gian chờ. Mặc định của thư viện này là **chờ vô hạn**.

Toàn bộ thiết kế thử-lại và hoàn-tiền của quy trình thanh toán được xây dựng dựa trên giả định rằng khi mạng có vấn đề thì lời gọi sẽ báo lỗi và rơi vào nhánh xử lý. Nhưng giả định đó chỉ đúng khi phía bên kia *từ chối kết nối*. Nếu phía bên kia nhận kết nối rồi treo (quá tải, kẹt cơ sở dữ liệu, mất gói tin), lời gọi sẽ đứng im mãi mãi.

Hậu quả dây chuyền:

- Luồng xử lý bị treo, không bao giờ rơi vào nhánh thử lại.
- Người dùng ngồi chờ vô thời hạn, không nhận được phản hồi nào.
- Luồng đó đang giữ khoá tài khoản, và vì khoá chỉ giữ được 30 giây (xem P-04) nên khoá sẽ tự hết hạn trong khi lời gọi trừ tiền vẫn đang bay — mở đường cho request khác chen vào cùng tài khoản.
- Nếu nhiều người cùng gặp tình huống này, số luồng xử lý của server sẽ cạn kiệt và cả payment-service ngừng phục vụ.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/RestTemplateConfig.java:14`

### Cách sửa

Cấu hình thời gian chờ khi tạo đối tượng gọi HTTP, thay vì dùng lệnh khởi tạo trần. Đề xuất mốc thời gian:

- Thời gian chờ thiết lập kết nối: khoảng 2 giây.
- Thời gian chờ nhận phản hồi: khoảng 5 giây.

Với ba lần thử cho mỗi bước, tổng thời gian xấu nhất của một bước là khoảng 15 giây — vẫn nằm trong ngưỡng người dùng chấp nhận được, và quan trọng hơn là **có điểm dừng**.

Cách làm là dùng `RestTemplateBuilder` của Spring Boot với hai tham số `setConnectTimeout` và `setReadTimeout`, thay cho `new RestTemplate()`.

---

## P-04. Khoá tài khoản chỉ giữ được 30 giây, ngắn hơn thời gian xử lý

**Mức độ:** Cao

### Nguyên nhân

Để tránh hai giao dịch cùng đụng vào một tài khoản, hệ thống dùng một cơ chế khoá đặt trên Redis. Khoá này được cấu hình: chờ tối đa 5 giây để giành khoá, và **giữ khoá tối đa 30 giây**. Sau 30 giây, khoá tự động được nhả ra, kể cả khi công việc chưa xong.

Con số 30 giây này quá ngắn so với thời gian tối đa mà quy trình thanh toán có thể chạy. Đếm số lời gọi mạng trong trường hợp xấu nhất:

- 3 lần thử trừ tiền
- 3 lần thử báo sang hệ thống học phí
- 1 lần đọc lại trạng thái học phí để đối chiếu
- 3 lần thử hoàn tiền

Tổng cộng 10 lời gọi mạng. Chỉ cần mỗi lời gọi mất 3 giây là đã vượt 30 giây. Mà theo P-03 thì các lời gọi này còn không có giới hạn thời gian, nên chỉ cần một lời gọi treo là chắc chắn vượt.

Khi khoá hết hạn giữa chừng, một request khác của cùng tài khoản có thể giành được khoá và chạy song song với request đang dở — đúng cái tình huống mà khoá sinh ra để ngăn.

Một điểm đã được viết đúng và **không nên sửa**: khối dọn dẹp ở cuối có kiểm tra "khoá này có còn thuộc về luồng hiện tại không" trước khi nhả. Nhờ vậy khi khoá đã hết hạn và bị luồng khác chiếm, luồng cũ sẽ không nhả nhầm khoá của người khác.

### Vị trí

- `payment-service/.../service/PaymentService.java:112` — dòng giành khoá với tham số thời gian giữ khoá

### Cách sửa

Cách sửa phụ thuộc vào việc P-03 có được sửa hay không:

- **Nếu sửa P-03 trước** (đặt giới hạn thời gian cho lời gọi HTTP): tính lại thời gian giữ khoá bằng công thức "số lời gọi tối đa × thời gian chờ mỗi lời gọi + dự phòng". Với 10 lời gọi × 5 giây + dự phòng, nên đặt khoảng 60 giây.
- **Cách tốt hơn:** dùng cơ chế tự động gia hạn khoá của Redisson. Nếu gọi `tryLock` mà không truyền tham số thời gian giữ khoá, Redisson sẽ tự động gia hạn khoá theo chu kỳ chừng nào luồng còn sống, và nhả ngay khi luồng kết thúc hoặc chết. Cách này loại bỏ hoàn toàn việc phải đoán con số.

Khuyến nghị dùng cách thứ hai, kết hợp với P-03 để đảm bảo luồng không treo vĩnh viễn.

---

## P-05. Thử lại một cách mù quáng với những lỗi mà thử lại cũng vô ích

**Mức độ:** Cao

### Nguyên nhân

Cả ba bước trừ tiền, báo học phí và hoàn tiền đều dùng chung một khuôn mẫu: bắt riêng hai loại lỗi có ý nghĩa nghiệp vụ (không đủ số dư, không tìm thấy), còn **mọi lỗi khác đều bị gom vào một nhánh chung rồi thử lại 3 lần**.

Vấn đề là nhánh chung đó không chỉ bắt lỗi mạng. Trong thư viện đang dùng, lỗi mạng và lỗi do phía bên kia trả về mã lỗi (403, 400, 500...) đều thuộc cùng một họ. Nghĩa là những lỗi sau cũng bị đưa vào vòng thử lại:

- **Sai khoá nội bộ (mã 403):** nếu biến môi trường chứa khoá bị đặt sai giữa các service, mọi lời gọi sẽ bị từ chối. Hệ thống sẽ thử lại 3 lần rồi bỏ cuộc, để giao dịch treo ở trạng thái "đang xử lý" và báo với người dùng là "hệ thống đang bận" — trong khi thực tế đây là lỗi cấu hình, thử lại một triệu lần cũng vẫn hỏng.
- **Lỗi từ tuition-service (mã 400):** phía tuition-service đang quy mọi lỗi bất ngờ về mã 400. Những lỗi đó cũng rơi vào vòng thử lại rồi treo giao dịch.

Hai hậu quả: một là che giấu nguyên nhân thật khiến việc tìm lỗi rất mất thời gian, hai là những giao dịch treo ở trạng thái "đang xử lý" không có ai xử lý tiếp (xem P-14).

### Vị trí

- `payment-service/.../service/PaymentService.java:159` — hàm `doDebit`
- `payment-service/.../service/PaymentService.java:183` — hàm `doMarkPaidWithSaga`
- `payment-service/.../service/PaymentService.java:239` — hàm `refundAndFail`

Cả ba đều có dòng bắt lỗi chung ở cuối vòng lặp.

### Cách sửa

Phân loại lỗi rõ ràng trước khi quyết định có thử lại hay không:

1. **Chỉ thử lại với lỗi thực sự thuộc về mạng** — mất kết nối, hết thời gian chờ, và lỗi 5xx từ phía bên kia. Trong thư viện đang dùng, đây là các trường hợp `ResourceAccessException` và `HttpServerErrorException`.
2. **Không thử lại với lỗi 4xx khác** ngoài hai loại đã bắt riêng. Với những lỗi này, nên ghi log ở mức nghiêm trọng kèm mã lỗi và nội dung phản hồi, đánh dấu giao dịch thất bại (và hoàn tiền nếu đã trừ), rồi trả về thông báo lỗi cho người dùng.
3. Nên tách riêng lỗi 403 (sai khoá nội bộ) thành một thông báo log rõ ràng kiểu "sai cấu hình khoá nội bộ giữa các service", vì đây là lỗi triển khai chứ không phải lỗi người dùng.

---

## P-06. In toàn bộ mã đăng nhập của người dùng ra log

**Mức độ:** Cao

### Nguyên nhân

Bộ lọc kiểm tra đăng nhập của payment-service đang ghi ra log hai dòng chứa **nguyên vẹn** mã đăng nhập (token) của người dùng: một dòng in cả header, một dòng in riêng phần token.

Mã đăng nhập này có giá trị tương đương mật khẩu trong suốt thời gian nó còn hiệu lực. Bất kỳ ai đọc được log — người vận hành, người có quyền vào máy chủ, hoặc hệ thống thu thập log tập trung nếu có — đều có thể sao chép token đó và **đóng giả người dùng** để gọi mọi API, bao gồm cả API thanh toán.

Đây là các dòng log rõ ràng được thêm vào lúc gỡ lỗi (nhìn tiền tố `=== [PAYMENT FILTER]` là biết) và bị quên xoá. Ngoài rủi ro bảo mật, chúng còn đang được ghi ở mức `info`, tức là in ra trong mọi lần chạy bình thường, làm log phình to vô ích.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/filter/JwtAuthenticationFilter.java:43` — dòng in header
- `payment-service/.../filter/JwtAuthenticationFilter.java:52` — dòng in token

Các dòng log gỡ lỗi khác trong cùng file (in đường dẫn, in mã người dùng) cũng nên hạ mức hoặc bỏ.

### Cách sửa

1. **Xoá hẳn hai dòng in token.** Không có lý do chính đáng nào để giữ chúng.
2. Nếu vẫn cần biết request có kèm token hay không để gỡ lỗi, chỉ ghi trạng thái chứ không ghi nội dung — ví dụ "có/không có header Authorization".
3. Các dòng log còn lại trong file nên chuyển từ mức `info` xuống mức `debug`, để bình thường không in ra mà chỉ bật khi cần gỡ lỗi.
4. Rà soát nhanh toàn dự án xem còn chỗ nào in token hoặc mật khẩu ra log không.

---

## P-07. Hai service khai báo cùng một hàng đợi email theo hai kiểu khác nhau

**Mức độ:** Cao

### Nguyên nhân

Email OTP được gửi qua một hàng đợi tên `email_queue`: payment-service bỏ tin nhắn vào, notification-service lấy ra và gửi mail. Cả hai service đều tự khai báo hàng đợi này khi khởi động — nhưng khai báo **khác nhau**:

- payment-service khai báo hàng đợi kèm cấu hình "hàng đợi dự phòng cho tin nhắn lỗi" (khi gửi mail thất bại thì tin nhắn được chuyển sang một hàng đợi khác để xử lý sau).
- notification-service khai báo hàng đợi trần, không có cấu hình đó.

RabbitMQ có quy tắc: nếu một hàng đợi đã tồn tại và có ai khai báo lại nó với thông số khác, nó sẽ từ chối và báo lỗi. Nghĩa là service nào khởi động **sau** sẽ gặp lỗi khi khai báo.

Điều làm lỗi này khó chịu là nó **phụ thuộc vào thứ tự khởi động container**, nên có lúc chạy được có lúc không:

- Nếu notification-service lên trước: payment-service sẽ lỗi khi khai báo, và cơ chế hàng đợi dự phòng cho tin nhắn lỗi coi như mất tác dụng.
- Nếu payment-service lên trước: notification-service sẽ lỗi khi khai báo, kênh nhận tin bị đóng và **email không được gửi đi**, dù payment-service vẫn báo thành công.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/RabbitMQConfig.java:26-27` — khai báo có kèm hàng đợi dự phòng
- `notification-service/src/main/java/com/tdtu/ibanking/notification/config/RabbitMQConfig.java:14` — khai báo trần

### Cách sửa

Phải làm cho hai bên khai báo **giống hệt nhau**. Có hai hướng:

- **Hướng khuyến nghị:** sửa notification-service khai báo giống payment-service, tức là cũng kèm cấu hình hàng đợi dự phòng. Giữ được tính năng "tin nhắn gửi mail lỗi thì không bị mất".
- **Hướng đơn giản hơn:** bỏ cấu hình hàng đợi dự phòng ở payment-service để cả hai cùng khai báo trần. Nhanh nhưng mất tính năng.

Lưu ý quan trọng: sau khi sửa, **phải xoá hàng đợi cũ trên RabbitMQ** rồi mới khởi động lại, vì hàng đợi đã tồn tại với thông số cũ sẽ không tự đổi theo. Cách nhanh nhất khi đang chạy trên Docker là xoá volume của RabbitMQ, hoặc vào trang quản trị RabbitMQ ở cổng 15672 xoá thủ công hàng đợi `email_queue`.

---

## P-08. Mã OTP dễ đoán và thiếu mất một giá trị

**Mức độ:** Trung bình

### Nguyên nhân

Dòng sinh mã OTP có hai vấn đề riêng biệt:

**Vấn đề thứ nhất — bộ sinh số không an toàn.** Bộ sinh số ngẫu nhiên đang dùng là loại thông thường, không phải loại dùng cho mục đích bảo mật. Nó tạo số theo một công thức toán học cố định, và hạt giống khởi tạo lấy từ đồng hồ hệ thống. Nghĩa là nếu biết được thời điểm mã OTP được tạo, người ta có thể tính ra dãy số mà nó sinh ra. Với một ứng dụng ngân hàng, OTP là lớp bảo vệ cuối cùng trước khi tiền rời khỏi tài khoản, nên việc dùng bộ sinh số đoán được là không chấp nhận được.

**Vấn đề thứ hai — thiếu một giá trị.** Cách viết hiện tại yêu cầu bộ sinh trả về một số trong khoảng từ 0 đến 999998 (giới hạn trên không bao gồm chính nó). Nghĩa là mã `999999` **không bao giờ được sinh ra**. Đây là lỗi lệch một đơn vị kinh điển. Ảnh hưởng thực tế rất nhỏ (mất 1 trong 1 triệu khả năng) nhưng vẫn là sai.

### Vị trí

- `payment-service/.../service/PaymentService.java:68`

### Cách sửa

Sửa cả hai vấn đề trong một lần:

1. Thay bộ sinh số thông thường bằng bộ sinh số dùng cho mục đích bảo mật — `java.security.SecureRandom` thay cho `java.util.Random`.
2. Đổi giới hạn từ `999999` thành `1000000` để bao trọn khoảng từ `000000` đến `999999`.
3. Nên tạo bộ sinh số **một lần** dưới dạng hằng số của lớp, thay vì tạo mới mỗi lần sinh OTP như hiện tại. Việc tạo mới mỗi lần vừa tốn tài nguyên vừa làm hạt giống khởi tạo phụ thuộc thời điểm nhiều hơn.

---

## P-09. Đếm số lần nhập sai OTP bị lệch một đơn vị

**Mức độ:** Trung bình

### Nguyên nhân

Ý định thiết kế là cho người dùng nhập sai OTP tối đa 3 lần rồi mới khoá giao dịch — hằng số trong code ghi rõ con số 3. Nhưng cách viết làm cho con số thực tế chỉ là 2.

Lý do là bộ đếm bị đặt giá trị khởi đầu **sai thời điểm**. Khi người dùng nhập lần đầu tiên, hàm kiểm tra thấy chưa có bộ đếm nào nên nó tạo bộ đếm và **đặt luôn giá trị 1** — mặc dù lúc đó người dùng còn chưa nhập sai lần nào. Sau đó nếu nhập sai thật thì hàm ghi nhận lỗi lại tăng bộ đếm lên 2. Diễn biến đầy đủ:

| Lần nhập | Bộ đếm lúc kiểm tra | Cho qua? | Bộ đếm sau khi nhập sai |
|----------|---------------------|----------|--------------------------|
| Lần 1 | chưa có → đặt thành 1 | Có | 2 |
| Lần 2 | 2 | Có (2 < 3) | 3 |
| Lần 3 | 3 | **Không** (3 không nhỏ hơn 3) | — |

Người dùng bị khoá ngay sau **2** lần sai, không phải 3. Điều này gây khó chịu không cần thiết, đặc biệt khi kết hợp với lỗi P-12 (gửi thiếu trường OTP cũng bị tính là một lần sai) — chỉ cần hai request lỗi định dạng là giao dịch bị khoá.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/service/RateLimiterService.java:33-46` — hàm `canAttemptOtp` và hàm `recordFailedAttempt`

### Cách sửa

Tách bạch hai việc: *kiểm tra* và *ghi nhận*. Hàm kiểm tra không nên có tác dụng phụ là đặt giá trị.

Cách sửa gọn nhất: khi chưa có bộ đếm, hàm kiểm tra chỉ trả về "cho qua" mà **không tạo bộ đếm**. Việc tạo bộ đếm để cho hàm ghi nhận lỗi làm, và hàm này vốn đã dùng lệnh tăng của Redis (lệnh này tự tạo bộ đếm bằng 1 nếu chưa có). Sau khi sửa, diễn biến sẽ đúng như thiết kế: sai lần 1 → bộ đếm 1, sai lần 2 → bộ đếm 2, sai lần 3 → bộ đếm 3, lần 4 bị chặn.

---

## P-10. Bộ đếm giới hạn không an toàn khi nhiều người gọi cùng lúc

**Mức độ:** Trung bình

### Nguyên nhân

Giới hạn "mỗi người chỉ được yêu cầu OTP 3 lần mỗi giờ" được cài đặt theo kiểu: đọc bộ đếm ra, so sánh với giới hạn, rồi ghi giá trị mới vào. Ba thao tác này là ba lệnh riêng biệt gửi tới Redis.

Khi nhiều request tới cùng lúc, chúng có thể **cùng đọc ra một giá trị cũ** rồi cùng kết luận là chưa vượt giới hạn, và cùng được cho qua. Ví dụ ba request cùng đọc ra giá trị 2, cả ba đều thấy 2 nhỏ hơn 3 nên đều được chấp nhận — kết quả là 5 OTP được gửi thay vì 3.

Ngoài ra, cách viết hiện tại chỉ đặt thời hạn sống cho bộ đếm ở nhánh tạo mới. Ở nhánh tăng giá trị thì không đụng tới thời hạn, nên hành vi phụ thuộc vào việc lệnh tăng của Redis có giữ nguyên thời hạn cũ hay không — tình cờ là có, nhưng đó là chi tiết dễ vỡ nếu sau này ai đó sửa lại.

Mức độ nghiêm trọng ở đây không cao vì kẻ tấn công cũng chỉ gửi thêm được vài email, nhưng đây là lỗi cơ chế đúng nghĩa và cũng lặp lại ở bộ đếm số lần nhập sai OTP.

### Vị trí

- `payment-service/.../service/RateLimiterService.java:22-31` — hàm `canRequestOtp`
- Cùng kiểu vấn đề ở hàm `canAttemptOtp` (dòng 33-46)

### Cách sửa

Dùng lệnh tăng của Redis làm thao tác đầu tiên thay vì đọc trước rồi ghi sau. Lệnh tăng là thao tác nguyên tử — dù bao nhiêu request gọi cùng lúc, mỗi lời gọi vẫn nhận về một giá trị khác nhau.

Trình tự đúng là:

1. Gọi lệnh tăng, lấy giá trị trả về.
2. Nếu giá trị trả về đúng bằng 1, nghĩa là mình vừa tạo bộ đếm — đặt thời hạn sống cho nó ngay lúc này.
3. So sánh giá trị trả về với giới hạn để quyết định cho qua hay từ chối.

---

## P-11. Mọi lỗi đều trả về cùng một mã 400

**Mức độ:** Trung bình

### Nguyên nhân

payment-service có một bộ xử lý lỗi tập trung, và bộ này bắt **mọi loại lỗi phát sinh lúc chạy** rồi trả về cùng một mã HTTP là 400 (nghĩa là "yêu cầu của bạn sai").

Toàn bộ nghiệp vụ của service này lại đang báo lỗi bằng cách ném ra lỗi chung chung kèm một dòng chữ tiếng Việt. Kết quả là những tình huống rất khác nhau về bản chất đều ra cùng một mã:

| Tình huống thực tế | Mã đúng nên trả | Mã đang trả |
|--------------------|-----------------|-------------|
| Số dư không đủ | 409 | 400 |
| Không tìm thấy giao dịch | 404 | 400 |
| Giao dịch không thuộc về bạn | 403 | 400 |
| Gửi OTP quá nhiều lần | 429 | 400 |
| Hệ thống đang bận, chưa xử lý xong | 503 | 400 |

Hai hậu quả:

**Về phía giao diện:** frontend không thể phân biệt các trường hợp để hiển thị đúng. Nó buộc phải đọc chuỗi tiếng Việt trong thông báo để đoán chuyện gì đã xảy ra — cách làm rất dễ vỡ, chỉ cần sửa một chữ trong thông báo là giao diện hỏng.

**Về phía bảo mật:** bộ xử lý này bắt cả những lỗi bất ngờ của hệ thống (lỗi kết nối cơ sở dữ liệu, lỗi lập trình...) và trả nguyên nội dung lỗi ra ngoài cho người dùng. Nội dung đó thường chứa thông tin nội bộ như tên bảng, tên máy chủ, đường dẫn file.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/GlobalExceptionHandler.java`
- Các chỗ ném lỗi rải rác trong `PaymentService.java` (dòng 40, 44, 47, 52, 56, 89, 92, 99, 105, 115, 118, 137, 143, 147, 163, 167)

### Cách sửa

1. **Tạo các lớp lỗi riêng cho từng tình huống nghiệp vụ**, thay vì dùng lỗi chung. Ví dụ: lỗi không đủ số dư, lỗi không tìm thấy giao dịch, lỗi không có quyền, lỗi vượt giới hạn, lỗi hệ thống bận. tuition-service và auth-service đã làm theo hướng này rồi, nên payment-service làm theo cho đồng bộ.
2. **Trong bộ xử lý lỗi tập trung, khai báo riêng cho từng lớp lỗi** và gán đúng mã HTTP tương ứng.
3. **Với lỗi chung không rõ nguyên nhân**, trả về mã 500 kèm một thông báo chung chung kiểu "đã có lỗi xảy ra, vui lòng thử lại", và ghi chi tiết lỗi vào log thay vì trả ra ngoài.
4. Nên thống nhất cấu trúc phần thân của phản hồi lỗi giữa ba service. Hiện tại payment-service khi lỗi kiểm tra dữ liệu thì trả về một đối tượng có nhiều khoá theo tên trường, còn khi lỗi khác thì trả về một khoá `message` — frontend phải xử lý hai dạng khác nhau.

---

## P-12. Trường OTP để trống vẫn lọt qua vòng kiểm tra dữ liệu

**Mức độ:** Trung bình

### Nguyên nhân

Đối tượng nhận dữ liệu của API xác nhận OTP có hai trường: mã giao dịch và mã OTP. Trường mã giao dịch được đánh dấu là bắt buộc, nhưng **trường OTP thì không** — nó chỉ được đánh dấu là "phải khớp mẫu 6 chữ số".

Điểm mấu chốt: quy tắc kiểm tra mẫu trong Java có một hành vi mặc định là **bỏ qua giá trị trống**. Nghĩa là nếu trường OTP không được gửi lên hoặc được gửi lên là rỗng, quy tắc này coi như hợp lệ và cho đi tiếp.

Hậu quả là một request thiếu trường OTP sẽ đi qua được vòng kiểm tra dữ liệu, xuống tới tầng nghiệp vụ, so sánh với OTP thật và tất nhiên là không khớp — nên **bị tính là một lần nhập sai OTP**.

Kết hợp với lỗi P-09 (thực tế chỉ cho sai 2 lần), chỉ cần gửi hai request thiếu trường OTP là giao dịch của người dùng bị khoá và họ phải làm lại từ đầu. Người dùng bình thường ít gặp, nhưng đây là cách phá hoại rất rẻ nếu ai đó biết mã giao dịch.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/dto/OtpVerifyRequest.java` — trường `otp` thiếu đánh dấu bắt buộc

### Cách sửa

1. Thêm đánh dấu bắt buộc cho trường OTP (`@NotBlank` là phù hợp nhất vì nó chặn cả giá trị trống lẫn chuỗi rỗng và chuỗi toàn khoảng trắng).
2. Thêm nội dung thông báo lỗi tiếng Việt cho cả hai trường, để phản hồi cho frontend thống nhất ngôn ngữ.
3. Xem thêm phần cách sửa của P-09 — kể cả sau khi sửa lỗi này, việc một request lỗi định dạng bị tính là một lần nhập sai OTP vẫn nên được xem lại.

---

## P-13. Cấu hình Redis trong file cấu hình không có tác dụng

**Mức độ:** Trung bình

### Nguyên nhân

File cấu hình của payment-service khai báo địa chỉ Redis theo đường dẫn `spring.redis`. Đây là đường dẫn của Spring Boot phiên bản 2. Từ Spring Boot 3 trở đi, nó đã được đổi thành `spring.data.redis`. Dự án đang dùng Spring Boot 3.2.4, nên **toàn bộ khối cấu hình Redis trong file yml đang bị hệ thống bỏ qua** — biến môi trường `REDIS_HOST` khai báo trong docker-compose cũng vô tác dụng theo.

Lý do hệ thống vẫn chạy được là do trùng hợp: lớp cấu hình Redisson tự tạo kết nối riêng với địa chỉ **ghi cứng thẳng trong code** là `redis://redis:6379`, và thư viện Redisson lại cung cấp luôn kết nối này cho phần còn lại của ứng dụng dùng chung. Chỉ có mật khẩu là vẫn đọc được, vì nó được lấy trực tiếp từ file yml chứ không đi qua cơ chế ánh xạ cấu hình.

Hai hậu quả:

- **Không chạy được ngoài Docker.** Tên máy chủ `redis` chỉ tồn tại trong mạng nội bộ của Docker. Ai muốn chạy thẳng trên máy để gỡ lỗi sẽ không kết nối được.
- **Đổi địa chỉ Redis phải sửa code**, không sửa được bằng biến môi trường như thiết kế.

Đây là loại lỗi âm thầm — không có thông báo nào, chỉ đơn giản là cấu hình bị bỏ qua.

### Vị trí

- `payment-service/src/main/resources/application.yml` — khối `spring.redis`
- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/RedissonConfig.java` — dòng ghi cứng `redis://redis:6379` và dòng đọc mật khẩu theo đường dẫn cũ

### Cách sửa

1. Đổi khối cấu hình trong file yml từ `spring.redis` sang `spring.data.redis`.
2. Trong lớp cấu hình Redisson, thay địa chỉ ghi cứng bằng cách đọc từ cấu hình: lấy máy chủ và cổng từ `spring.data.redis.host` và `spring.data.redis.port`, rồi ghép thành chuỗi địa chỉ. Nhớ đặt giá trị mặc định `localhost` và `6379` để chạy được trên máy cá nhân.
3. Đổi luôn đường dẫn đọc mật khẩu sang `spring.data.redis.password`.
4. Kiểm tra chéo: `docker-compose.yml` đã truyền sẵn `REDIS_HOST` và `REDIS_PASSWORD`, nên sau khi sửa thì phần đó không cần đụng tới.

---

## P-14. Không có cách nào tra cứu lại giao dịch bị treo

**Mức độ:** Trung bình

### Nguyên nhân

Thiết kế hiện tại chấp nhận rằng sẽ có những giao dịch không thể kết thúc tự động. Cụ thể có ba tình huống được code ghi log kèm dòng chữ "cần đối soát tay":

- Gọi trừ tiền thất bại sau 3 lần thử — không biết tiền đã bị trừ hay chưa.
- Báo học phí thất bại và cũng không đọc lại được trạng thái để đối chiếu.
- Hoàn tiền thất bại sau 3 lần thử — tiền đã trừ nhưng chưa trả lại được.

Trong cả ba tình huống, giao dịch nằm lại vĩnh viễn ở trạng thái "đang xử lý". Vấn đề là **không có công cụ nào để xử lý tiếp**:

- Không có API nào cho người dùng xem lại trạng thái giao dịch của mình. Người dùng bấm thanh toán, nhận thông báo "hệ thống đang bận", và sau đó hoàn toàn mù tịt — không biết tiền có bị trừ không, có nên thử lại không.
- Không có API nào cho quản trị viên xem danh sách giao dịch đang treo.
- Không có tác vụ chạy nền để tự động dò và xử lý các giao dịch treo.
- Kho dữ liệu giao dịch không có sẵn câu truy vấn nào theo người dùng hay theo trạng thái — muốn tra cũng phải vào thẳng cơ sở dữ liệu gõ lệnh.

Nói cách khác, cụm từ "cần đối soát tay" trong log hiện tại không có ai và không có công cụ nào để thực hiện.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/repository/TransactionRepository.java` — không có phương thức truy vấn nào
- `payment-service/src/main/java/com/tdtu/ibanking/payment/controller/PaymentController.java` — chỉ có 2 API là khởi tạo và xác nhận OTP
- Các dòng ghi log "cần đối soát tay" ở `PaymentService.java` dòng 176, 214, 260

### Cách sửa

Xếp theo thứ tự ưu tiên, tuỳ thời gian còn lại mà làm tới đâu:

1. **Tối thiểu — thêm API xem chi tiết một giao dịch.** Nhận vào mã giao dịch, kiểm tra giao dịch có thuộc về người gọi không, trả về trạng thái, số tiền, thời gian và thông báo lỗi nếu có. Chỉ cần vậy là người dùng đã hết mù tịt và frontend có thể tự hỏi lại trạng thái sau khi gặp lỗi "hệ thống đang bận".
2. **Nên có — thêm API xem lịch sử giao dịch của người dùng**, kèm câu truy vấn tương ứng trong kho dữ liệu.
3. **Nếu còn thời gian — thêm tác vụ chạy nền đối soát.** Chạy theo chu kỳ vài phút một lần, tìm những giao dịch ở trạng thái "đang xử lý" đã quá lâu, đọc lại trạng thái thật bên tuition-service và auth-service rồi kết luận: nếu học phí đã ghi nhận đúng mã giao dịch này thì đánh dấu thành công, còn nếu chưa thì hoàn tiền và đánh dấu thất bại.

---

## P-15. Gửi email thất bại làm hỏng cả giao dịch đã thành công

**Mức độ:** Trung bình

### Nguyên nhân

Sau khi thanh toán xong xuôi — tiền đã trừ, học phí đã ghi nhận, trạng thái đã đặt là thành công — hệ thống gọi thêm một bước gửi email xác nhận.

Bước này có bọc xử lý lỗi, nhưng chỉ bắt loại lỗi liên quan tới gọi HTTP. Trong khi việc bỏ tin nhắn vào hàng đợi email lại ném ra một loại lỗi hoàn toàn khác. Loại lỗi đó **không được bắt**, nên nó thoát ra ngoài, bay lên bộ xử lý lỗi tập trung, và người dùng nhận về **mã 400 kèm thông báo lỗi**.

Tình huống thực tế: RabbitMQ chết sau khi thanh toán đã hoàn tất. Người dùng thấy màn hình báo lỗi, trong khi tiền đã bị trừ và học phí đã đóng xong. Phản xạ tự nhiên là bấm thanh toán lại — dẫn thẳng tới các vấn đề ở P-01 và P-02.

Một biến thể tương tự nằm ở bước khởi tạo thanh toán: giao dịch được lưu và OTP được ghi vào bộ nhớ tạm trước, rồi mới gửi email. Nếu bước gửi email hỏng, người dùng nhận lỗi nhưng giao dịch đã tồn tại và một lượt gửi OTP đã bị trừ. Tình huống này nhẹ hơn vì người dùng thực sự không nhận được OTP nên báo lỗi là hợp lý — nhưng lượt gửi bị trừ oan thì vẫn nên xử lý (xem P-18).

### Vị trí

- `payment-service/.../service/PaymentService.java:263` — hàm `sendSuccessEmail`, khối bắt lỗi chỉ khai báo loại lỗi HTTP
- `payment-service/.../service/PaymentService.java:274` — dòng bỏ tin nhắn vào hàng đợi
- `payment-service/.../service/PaymentService.java:76` — dòng tương tự ở bước khởi tạo

### Cách sửa

1. **Ở bước gửi email xác nhận sau khi thành công:** mở rộng khối bắt lỗi để bắt mọi loại lỗi, không riêng lỗi HTTP. Nguyên tắc là **email chỉ là việc phụ, không được phép làm hỏng kết quả của việc chính**. Khi lỗi thì ghi log cảnh báo rồi đi tiếp, người dùng vẫn phải nhận được thông báo thanh toán thành công.
2. **Ở bước khởi tạo thanh toán:** ngược lại, ở đây gửi email *là* việc chính (không có OTP thì không thanh toán được), nên báo lỗi cho người dùng là đúng. Nhưng nên bọc khối bắt lỗi để dọn dẹp trước khi báo: xoá OTP vừa ghi, trả lại lượt gửi vừa trừ, và đánh dấu giao dịch vừa tạo là thất bại. Sau đó mới báo lỗi ra ngoài với thông báo rõ ràng kiểu "không gửi được mã OTP, vui lòng thử lại".

---

## P-16. Có thể lỗi khi service học phí trả về giá trị trống

**Mức độ:** Nhẹ

### Nguyên nhân

Ở bước khởi tạo thanh toán, sau khi lấy thông tin học phí về, code kiểm tra xem khoản học phí đó đã đóng hay chưa bằng cách đọc thẳng trường "đã đóng".

Trường này được khai báo kiểu cho phép giá trị trống, nhưng chỗ kiểm tra lại dùng nó trực tiếp như một giá trị đúng/sai. Java sẽ tự động chuyển đổi, và nếu giá trị là trống thì phép chuyển đổi này gây lỗi ngay lập tức.

Hiện tại chưa xảy ra vì API bên tuition-service luôn điền giá trị cho trường này. Nhưng đây là một sự phụ thuộc ngầm, không được ghi ở đâu cả — chỉ cần tuition-service đổi cấu trúc phản hồi, hoặc một trường bị đổi tên khiến việc đọc dữ liệu trả về giá trị trống, là payment-service sẽ lỗi với một thông báo khó hiểu.

Kiểu vấn đề tương tự cũng có ở chỗ đọc số dư người dùng và số tiền học phí ở ngay bên dưới — nếu một trong hai là trống thì phép so sánh cũng lỗi.

### Vị trí

- `payment-service/.../service/PaymentService.java:48` — dòng kiểm tra trường "đã đóng"
- `payment-service/.../service/PaymentService.java:56` — dòng so sánh số dư với số tiền

### Cách sửa

Kiểm tra giá trị trống một cách tường minh thay vì đọc thẳng. Với trường đúng/sai, cách an toàn là dùng `Boolean.TRUE.equals(...)` — cách viết này trả về "sai" khi giá trị là trống thay vì gây lỗi. Với số dư và số tiền, nên kiểm tra khác trống trước khi so sánh, và nếu trống thì báo lỗi rõ ràng kiểu "không đọc được thông tin học phí" thay vì để lỗi kỹ thuật thoát ra ngoài.

---

## P-17. Tạo nhiều giao dịch chờ cho cùng một khoản học phí

**Mức độ:** Nhẹ

### Nguyên nhân

Mỗi lần người dùng bấm nút thanh toán, hệ thống tạo một bản ghi giao dịch mới, không hề kiểm tra xem đã có giao dịch nào đang chờ cho đúng khoản học phí đó chưa.

Vì giới hạn gửi OTP là 3 lần mỗi giờ, một người có thể tạo tối đa 3 giao dịch chờ cho cùng một khoản. Nếu họ xác nhận cả ba, giao dịch đầu tiên sẽ thành công, hai giao dịch còn lại sẽ trừ tiền rồi bị từ chối ở bước báo học phí (vì khoản đó đã có người đóng rồi) và được hoàn tiền lại.

Về mặt tiền bạc thì không mất mát — cơ chế hoàn tiền chạy đúng. Nhưng có ba điểm không hay: người dùng thấy tiền bị trừ rồi được trả lại một cách khó hiểu; bảng giao dịch tích tụ các bản ghi thất bại vô nghĩa; và mỗi lần như vậy là thêm 2-3 lời gọi mạng không cần thiết.

### Vị trí

- `payment-service/.../service/PaymentService.java:39-66` — hàm `initiatePayment`, đoạn tạo và lưu giao dịch

### Cách sửa

Trước khi tạo giao dịch mới, tra xem đã có giao dịch nào của cùng người dùng, cùng khoản học phí, đang ở trạng thái chờ hoặc đang xử lý hay không.

- Nếu có và mã OTP của nó vẫn còn hiệu lực: trả về chính giao dịch đó thay vì tạo mới, để người dùng dùng lại mã OTP đã nhận.
- Nếu có nhưng mã OTP đã hết hạn: đánh dấu giao dịch cũ là hết hạn, rồi tạo giao dịch mới.

Cần thêm một câu truy vấn tương ứng vào kho dữ liệu giao dịch — việc này trùng với phần cần làm ở P-14.

---

## P-18. Bị trừ lượt gửi OTP dù chưa hề gửi OTP nào

**Mức độ:** Nhẹ

### Nguyên nhân

Ở bước khởi tạo thanh toán, việc kiểm tra và trừ lượt gửi OTP được đặt ở **dòng đầu tiên** của hàm, trước tất cả các bước kiểm tra khác.

Nghĩa là lượt bị trừ ngay cả khi request đó chắc chắn không dẫn tới việc gửi OTP nào:

- Nhập sai mã số sinh viên → không tìm thấy học phí → lỗi, nhưng lượt đã mất.
- Khoản học phí đã được đóng rồi → lỗi, lượt đã mất.
- Số dư không đủ → lỗi, lượt đã mất.

Với giới hạn chỉ 3 lượt mỗi giờ, chỉ cần gõ nhầm mã số sinh viên ba lần là người dùng bị khoá một tiếng đồng hồ mà chưa hề nhận được email nào. Đây là trải nghiệm rất tệ cho một lỗi rất dễ mắc.

### Vị trí

- `payment-service/.../service/PaymentService.java:40` — lời gọi kiểm tra giới hạn nằm ngay đầu hàm `initiatePayment`

### Cách sửa

Tách việc kiểm tra và việc trừ lượt thành hai bước ở hai vị trí khác nhau:

1. **Đầu hàm:** chỉ *kiểm tra* xem người dùng đã hết lượt chưa, không trừ. Nếu hết thì từ chối ngay.
2. **Ngay trước khi bỏ email OTP vào hàng đợi:** mới thực sự *trừ* một lượt.

Như vậy chỉ những lần thực sự gửi OTP mới bị tính. Cần tách hàm hiện tại trong lớp quản lý giới hạn thành hai hàm riêng.

---

## P-19. Tạo giao dịch mới là xoá sạch số lần nhập sai OTP

**Mức độ:** Nhẹ

### Nguyên nhân

Hai bộ đếm trong hệ thống đang được gắn vào hai thứ khác nhau:

- Số lần **yêu cầu** OTP được đếm theo **người dùng**, giới hạn 3 lần mỗi giờ.
- Số lần **nhập sai** OTP được đếm theo **mã giao dịch**, giới hạn 3 lần.

Vì bộ đếm thứ hai gắn với mã giao dịch, mỗi giao dịch mới sẽ có một bộ đếm mới tinh bằng 0. Nghĩa là người dùng có thể nhập sai OTP tới ngưỡng bị khoá, rồi chỉ cần tạo một giao dịch mới là được nhập sai tiếp từ đầu.

Trên thực tế điều này không nguy hiểm lắm, vì giới hạn 3 lần yêu cầu OTP mỗi giờ đã chặn phần lớn. Tổng số lần đoán OTP tối đa hiện tại là khoảng 3 giao dịch × 2 lần sai = 6 lần mỗi giờ, trên không gian 1 triệu mã — xác suất đoán trúng vẫn cực thấp. Nhưng đây là lỗ hổng thiết kế nên biết, và nó sẽ trở nên đáng lo nếu ai đó nới giới hạn số lần yêu cầu OTP lên.

### Vị trí

- `payment-service/.../service/RateLimiterService.java` — hằng số khoá của hai bộ đếm dùng hai loại định danh khác nhau

### Cách sửa

Thêm một bộ đếm thứ ba: đếm tổng số lần nhập sai OTP **theo người dùng**, không theo giao dịch, với thời hạn sống khoảng một giờ và giới hạn khoảng 5-10 lần. Bộ đếm này chỉ được xoá khi có một giao dịch thành công. Bộ đếm theo giao dịch vẫn giữ nguyên để bảo vệ từng giao dịch riêng lẻ.

---

## P-20. Một API trả về chữ thường trong khi các API khác trả về JSON

**Mức độ:** Nhẹ

### Nguyên nhân

API xác nhận OTP trả về một chuỗi ký tự trần là `Payment successful`. Trong khi đó:

- API khởi tạo thanh toán trả về một đối tượng JSON gồm mã giao dịch, số tiền, số dư.
- Mọi phản hồi lỗi đều trả về đối tượng JSON có khoá `message`.

Sự không nhất quán này buộc frontend phải xử lý riêng cho API này. Ngoài ra chuỗi trả về bằng tiếng Anh trong khi toàn bộ thông báo còn lại là tiếng Việt.

Vấn đề thực chất hơn: phản hồi thành công hiện không mang theo thông tin gì hữu ích. Sau khi thanh toán xong, frontend không biết số dư còn lại là bao nhiêu, giao dịch mã gì, thời gian nào — muốn biết phải gọi thêm API khác, mà API đó lại chưa có (xem P-14).

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/controller/PaymentController.java` — kiểu trả về của phương thức xác nhận OTP
- `payment-service/.../service/PaymentService.java:94` và `:155` — hai chỗ trả về chuỗi

### Cách sửa

Tạo một đối tượng phản hồi riêng cho việc thanh toán thành công, gồm ít nhất: mã giao dịch, trạng thái, số tiền đã trả, số dư còn lại sau khi trừ, thời điểm hoàn tất, và một thông báo tiếng Việt. Số dư còn lại đã có sẵn trong kết quả trả về của bước trừ tiền, chỉ cần chuyền tiếp ra ngoài thay vì bỏ đi như hiện tại.

---

## P-21. So sánh OTP theo cách có thể bị đo thời gian, và OTP lưu dạng thô

**Mức độ:** Nhẹ

### Nguyên nhân

Hai điểm nhỏ về bảo mật của mã OTP:

**Thứ nhất — cách so sánh.** Mã OTP người dùng nhập được so với mã đã lưu bằng phép so sánh chuỗi thông thường. Phép này dừng ngay khi gặp ký tự đầu tiên khác nhau, nên thời gian thực hiện phụ thuộc vào số ký tự đầu khớp được. Về lý thuyết, kẻ tấn công có thể đo thời gian phản hồi để dò từng ký tự một, giảm số lần đoán từ 1 triệu xuống còn vài chục. Trên thực tế điều này gần như không khả thi qua đường mạng vì độ nhiễu quá lớn, và giới hạn số lần thử cũng đã chặn — nhưng đáng chú ý là hai chỗ khác trong dự án (kiểm tra khoá nội bộ ở tuition-service và auth-service) đã dùng cách so sánh an toàn rồi, nên chỗ này không nhất quán.

**Thứ hai — cách lưu trữ.** Mã OTP được lưu vào Redis dưới dạng nguyên bản. Ai truy cập được Redis sẽ đọc thẳng ra mã OTP của mọi giao dịch đang chờ. Cách làm chuẩn hơn là chỉ lưu dấu vân của mã chứ không lưu mã, tương tự cách lưu mật khẩu.

### Vị trí

- `payment-service/.../service/PaymentService.java:103` — phép so sánh chuỗi
- `payment-service/.../service/PaymentService.java:69` — dòng ghi OTP vào Redis

### Cách sửa

1. Đổi phép so sánh sang cách chạy hết chuỗi bất kể khớp hay không — `MessageDigest.isEqual` trên mảng byte của hai chuỗi, giống cách hai service kia đã làm.
2. Nếu muốn làm tới: băm mã OTP trước khi ghi vào Redis, và khi kiểm tra thì băm mã người dùng nhập rồi so hai giá trị băm. Cách này khiến người đọc được Redis cũng không suy ra được mã gốc.

Hai việc này độc lập nhau; việc 1 rẻ và nên làm, việc 2 tuỳ mức độ đầu tư.

---

## P-22. Mã đăng nhập của người dùng bị gắn kèm vào mọi lời gọi đi ra ngoài

**Mức độ:** Nhẹ

### Nguyên nhân

Đối tượng gọi HTTP của payment-service được cài một bộ chặn: mỗi lần gọi đi, nó lấy mã đăng nhập từ request mà người dùng vừa gửi tới và **gắn vào mọi lời gọi đi ra**, không phân biệt gọi tới đâu.

Cách này hiện tại đang hoạt động đúng, và thậm chí còn là thứ duy nhất giúp lời gọi lấy thông tin người dùng qua được vòng kiểm tra của auth-service (lời gọi đó không kèm khoá nội bộ, nên phải dựa hoàn toàn vào mã đăng nhập được chuyền tiếp).

Nhưng đây là một cách làm rủi ro về lâu dài, vì ba lý do:

- **Rò rỉ phạm vi rộng.** Mã đăng nhập của người dùng bị gửi cả sang tuition-service, nơi hoàn toàn không cần nó. Thêm một service mới vào là mã đăng nhập cũng tự động bay sang đó.
- **Phụ thuộc ngầm và dễ vỡ.** Bộ chặn này chỉ hoạt động khi lời gọi xuất phát từ trong một request web đang xử lý. Nếu sau này có tác vụ chạy nền — chẳng hạn tác vụ đối soát đề xuất ở P-14 — thì không có mã đăng nhập nào để lấy, và lời gọi lấy thông tin người dùng sẽ bị từ chối. Lỗi này sẽ rất khó truy vì code không hề thể hiện sự phụ thuộc đó.
- **Không thấy được khi đọc code.** Nhìn vào lời gọi lấy thông tin người dùng, không có dấu hiệu nào cho biết nó đang được xác thực bằng gì.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/RestTemplateConfig.java` — toàn bộ đoạn cài bộ chặn
- `payment-service/src/main/java/com/tdtu/ibanking/payment/client/AuthServiceClient.java` — hàm lấy thông tin người dùng, chỗ duy nhất phụ thuộc vào bộ chặn này

### Cách sửa

Cho lời gọi lấy thông tin người dùng dùng khoá nội bộ giống như hai lời gọi trừ tiền và hoàn tiền, thay vì dựa vào mã đăng nhập được chuyền tiếp. Cụ thể:

1. Sửa lời gọi lấy thông tin người dùng để gắn khoá nội bộ vào phần đầu request.
2. Bổ sung đường dẫn lấy thông tin người dùng vào danh sách được bảo vệ bằng khoá nội bộ ở auth-service, để nó nhận ra người gọi là service nội bộ (auth-service đã có sẵn cơ chế này, chỉ cần thêm đường dẫn vào danh sách).
3. Sau đó bỏ hẳn bộ chặn chuyền tiếp mã đăng nhập.

Lưu ý: bước 2 phải làm **trước** bước 3, nếu không lời gọi sẽ bị từ chối trong khoảng thời gian giữa hai bước.

---

## P-23. Mở đường cho một chức năng không hề được cài đặt

**Mức độ:** Nhẹ

### Nguyên nhân

Cấu hình bảo mật của payment-service khai báo rằng đường dẫn `/actuator/**` được truy cập tự do không cần đăng nhập, và bộ lọc kiểm tra đăng nhập cũng có một nhánh riêng để bỏ qua đường dẫn này.

Nhưng payment-service **không hề khai báo thư viện actuator** trong file cấu hình phụ thuộc. Nghĩa là những đường dẫn đó không tồn tại, và toàn bộ hai đoạn cấu hình trên là cấu hình chết.

Hiện tại vô hại. Nhưng nếu sau này ai đó thêm thư viện actuator vào để phục vụ kiểm tra sức khoẻ dịch vụ, các đường dẫn quản trị sẽ **tự động mở toang** mà không ai để ý, vì đường mở sẵn đã nằm đó từ trước. Trong số các đường dẫn của actuator có những đường dẫn để lộ toàn bộ biến môi trường và cấu hình — bao gồm cả khoá bí mật.

Một điểm liên quan: docker-compose không khai báo kiểm tra sức khoẻ cho bất kỳ service Java nào, và api-gateway chỉ khai báo phụ thuộc suông. Nghĩa là gateway có thể khởi động xong trước khi các service phía sau sẵn sàng, gây lỗi ở vài request đầu tiên sau khi khởi động cụm.

### Vị trí

- `payment-service/src/main/java/com/tdtu/ibanking/payment/config/SecurityConfig.java` — dòng cho phép truy cập tự do `/actuator/**`
- `payment-service/.../filter/JwtAuthenticationFilter.java` — nhánh bỏ qua đường dẫn actuator
- `payment-service/pom.xml` — không có khai báo thư viện actuator

### Cách sửa

Chọn một trong hai hướng, đừng để lửng lơ như hiện tại:

- **Hướng dọn dẹp:** xoá cả hai đoạn cấu hình liên quan tới actuator.
- **Hướng bổ sung:** thêm thư viện actuator vào, nhưng **chỉ mở đúng đường dẫn kiểm tra sức khoẻ**, không mở cả nhóm. Sau đó thêm khai báo kiểm tra sức khoẻ cho các service trong docker-compose, và đổi phần khai báo phụ thuộc của gateway sang dạng chờ service thật sự sẵn sàng.

Hướng thứ hai tốt hơn cho việc chạy demo ổn định.

---

## P-24. Thông tin kết nối cơ sở dữ liệu mặc định không dùng được

**Mức độ:** Nhẹ

### Nguyên nhân

File cấu hình của payment-service đặt giá trị mặc định cho tài khoản cơ sở dữ liệu là `payment_user` với mật khẩu `payment_pass`. Nhưng tài khoản này **không tồn tại** — file khởi tạo cơ sở dữ liệu chỉ tạo ba cơ sở dữ liệu, không tạo tài khoản nào, và PostgreSQL chỉ có sẵn tài khoản `postgres`.

Trong Docker thì không lộ ra, vì docker-compose truyền biến môi trường đè lên giá trị mặc định. Nhưng ai chạy thẳng payment-service trên máy để gỡ lỗi sẽ gặp lỗi đăng nhập cơ sở dữ liệu và mất thời gian tìm nguyên nhân, vì thông báo lỗi không gợi ý gì về việc tài khoản này chưa từng được tạo.

Có một điểm rối thêm: docker-compose truyền cùng một biến `DB_USER` cho cả ba service, nên payment-service thực tế đang dùng chung tài khoản với auth-service và tuition-service. Nghĩa là ý định ban đầu là mỗi service một tài khoản riêng, nhưng việc này chưa được làm.

### Vị trí

- `payment-service/src/main/resources/application.yml` — giá trị mặc định của tên đăng nhập và mật khẩu
- `init-db.sql` — chỉ tạo cơ sở dữ liệu, không tạo tài khoản
- `docker-compose.yml` — truyền chung một biến `DB_USER` cho ba service

### Cách sửa

Chọn một trong hai:

- **Nhanh:** đổi giá trị mặc định trong file cấu hình thành `postgres`/`postgres` cho khớp với thực tế, giống hai service kia. Sửa một dòng là xong.
- **Đúng bài hơn:** bổ sung vào `init-db.sql` các lệnh tạo tài khoản riêng cho từng service và cấp quyền tương ứng trên đúng cơ sở dữ liệu của nó, rồi sửa docker-compose để truyền tên đăng nhập riêng cho từng service. Cách này giúp một service bị chiếm quyền không đọc được dữ liệu của service khác.

---

## Đề xuất thứ tự xử lý

**Bắt buộc sửa trước khi demo hoặc nộp bài**
P-01, P-02, P-06, P-07

**Nên sửa**
P-03, P-04, P-05, P-08, P-09, P-11, P-12, P-15

**Sửa nếu còn thời gian**
P-10, P-13, P-14, P-16, P-17, P-18, và các mục còn lại

---

## Những chỗ đã làm ĐÚNG, không nên sửa

Ghi lại để tránh sửa nhầm khi xử lý các lỗi trên:

- **Chống trừ tiền hai lần bằng bảng ghi sổ ở auth-service.** Ý tưởng đúng và cần giữ. Chỉ cần bổ sung phần xét trạng thái đã hoàn tiền như mô tả ở P-01.
- **Cơ chế bảo đảm báo học phí chỉ ăn một lần ở tuition-service.** Cùng mã giao dịch thì trả về kết quả cũ, khác mã giao dịch thì báo xung đột. Xử lý đúng.
- **Nhánh đối chiếu sau khi thử lại hết lượt** trong bước báo học phí: đọc lại trạng thái học phí rồi so mã giao dịch để quyết định thành công hay hoàn tiền. Đây là phần được thiết kế tốt nhất của quy trình.
- **Kiểm tra "khoá còn thuộc về mình không" trước khi nhả khoá.** Tránh được lỗi nhả nhầm khoá của luồng khác. Giữ nguyên khi sửa P-04.
- **So sánh khoá nội bộ theo cách chống đo thời gian** ở cả tuition-service và auth-service.
- **Kiểm tra quyền sở hữu giao dịch** trước khi xử lý OTP — đúng và cần thiết.
- **Khoá số tiền tại thời điểm khởi tạo** thay vì đọc lại lúc xác nhận. Đúng về nghiệp vụ: người dùng trả đúng số tiền đã được báo.
