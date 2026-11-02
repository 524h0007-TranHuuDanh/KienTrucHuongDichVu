package com.tdtu.ibanking.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.tdtu.ibanking.payment.dto.BalanceResponse;
import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.dto.PaymentSuccessResponse;
import com.tdtu.ibanking.payment.dto.TransactionHistoryItem;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.exception.InsufficientBalanceException;
import com.tdtu.ibanking.payment.exception.InvalidOtpException;
import com.tdtu.ibanking.payment.exception.TuitionNotFoundException;
import com.tdtu.ibanking.payment.support.AbstractPaymentIT;

/**
 * Luồng thanh toán đầu-cuối trên Postgres + Redis thật.
 *
 * <p>Mỗi test dùng một userId ngẫu nhiên riêng nên hạn mức 3 OTP/giờ của test này
 * không đụng test kia (Redis cũng đã được FLUSHALL ở {@code @BeforeEach}).
 */
class PaymentServiceTest extends AbstractPaymentIT {

    private static final BigDecimal AMOUNT = new BigDecimal("5000000.00");
    private static final BigDecimal BALANCE = new BigDecimal("10000000.00");

    /** Stub tuition chưa đóng + user đủ số dư, rồi gọi initiatePayment. */
    private PaymentInitResponse initiate(UUID userId, String mssv) {
        TuitionInfo tuition = unpaidTuition(mssv, AMOUNT);
        when(tuitionServiceClient.getTuitionByMssv(mssv)).thenReturn(tuition);
        when(authServiceClient.getUserInfo(userId)).thenReturn(userWithBalance(userId, BALANCE));
        return paymentService.initiatePayment(mssv, userId);
    }

    private Transaction reload(UUID transactionId) {
        return transactionRepository.findById(transactionId).orElseThrow();
    }

    // ------------------------------------------------------------------ (a)

    @Test
    @DisplayName("initiate sinh OTP: transaction PENDING + OTP 6 số trong Redis, TTL <= 5 phút")
    void initiatePayment_sinhOtpVaLuuRedis() {
        UUID userId = UUID.randomUUID();

        PaymentInitResponse response = initiate(userId, "52100001");

        assertThat(response.getTransactionId()).isNotNull();
        assertThat(response.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(response.getBalance()).isEqualByComparingTo(BALANCE);

        Transaction saved = reload(response.getTransactionId());
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getAmount()).isEqualByComparingTo(AMOUNT);

        assertThat(otpKeyExists(response.getTransactionId())).isTrue();
        assertThat(readOtp(response.getTransactionId())).matches("^[0-9]{6}$");

        long ttl = otpTtlSeconds(response.getTransactionId());
        assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(5 * 60L);
    }

    // ------------------------------------------------------------------ (b)

    @Test
    @DisplayName("OTP sai giảm remainingAttempts; sai đủ 3 lần thì FAILED và OTP bị xoá")
    void verifyOtp_saiBaLan_thiFailedVaXoaOtp() {
        UUID userId = UUID.randomUUID();
        PaymentInitResponse init = initiate(userId, "52100002");
        UUID txId = init.getTransactionId();
        String wrongOtp = wrongOtpFor(readOtp(txId));

        // Lần 1 và 2: chỉ trừ lượt, giao dịch vẫn PENDING và OTP vẫn còn.
        for (int expectedRemaining : new int[] {2, 1}) {
            InvalidOtpException ex = catchThrowableOfType(
                    () -> paymentService.verifyOtpAndPay(txId, wrongOtp, userId), InvalidOtpException.class);
            assertThat(ex).isNotNull();
            assertThat(ex.getRemainingAttempts()).isEqualTo(expectedRemaining);
            assertThat(reload(txId).getStatus()).isEqualTo(TransactionStatus.PENDING);
            assertThat(otpKeyExists(txId)).isTrue();
        }

        // Lần 3: hết lượt ngay tại lần sai này -> khai tử giao dịch + xoá OTP.
        InvalidOtpException last = catchThrowableOfType(
                () -> paymentService.verifyOtpAndPay(txId, wrongOtp, userId), InvalidOtpException.class);
        assertThat(last).isNotNull();
        assertThat(last.getRemainingAttempts()).isZero();

        Transaction failed = reload(txId);
        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Nhập sai OTP quá số lần cho phép");
        assertThat(otpKeyExists(txId)).isFalse();

        verify(authServiceClient, never()).debit(any(), any(), any());
    }

    // ------------------------------------------------------------------ (c)

    @Test
    @DisplayName("OTP đúng: debit rồi markPaid, transaction SUCCESS")
    void verifyOtp_dung_thiDebitRoiMarkPaidVaSuccess() {
        UUID userId = UUID.randomUUID();
        PaymentInitResponse init = initiate(userId, "52100003");
        UUID txId = init.getTransactionId();
        String realOtp = readOtp(txId);

        when(authServiceClient.debit(eq(userId), any(BigDecimal.class), eq(txId)))
                .thenReturn(new BalanceResponse(userId, BALANCE.subtract(AMOUNT)));
        when(tuitionServiceClient.markPaid(any(UUID.class), eq(txId))).thenReturn(null);

        PaymentSuccessResponse response = paymentService.verifyOtpAndPay(txId, realOtp, userId);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(response.getMessage()).isEqualTo("Thanh toán thành công");
        assertThat(response.getAmountPaid()).isEqualByComparingTo(AMOUNT);
        assertThat(response.getRemainingBalance()).isEqualByComparingTo(BALANCE.subtract(AMOUNT));

        assertThat(reload(txId).getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(otpKeyExists(txId)).isFalse();

        verify(authServiceClient).debit(eq(userId), any(BigDecimal.class), eq(txId));
        verify(tuitionServiceClient).markPaid(any(UUID.class), eq(txId));
        verify(authServiceClient, never()).credit(any(), any(), any());
    }

    // ------------------------------------------------------------------ (d)

    @Test
    @DisplayName("Saga bù trừ: markPaid trả 409 thì hoàn tiền đúng số đã trừ và transaction FAILED")
    void markPaidConflict_thiHoanTienVaFailed() {
        UUID userId = UUID.randomUUID();
        PaymentInitResponse init = initiate(userId, "52100004");
        UUID txId = init.getTransactionId();
        String realOtp = readOtp(txId);

        when(authServiceClient.debit(eq(userId), any(BigDecimal.class), eq(txId)))
                .thenReturn(new BalanceResponse(userId, BALANCE.subtract(AMOUNT)));

        HttpClientErrorException.Conflict conflict = (HttpClientErrorException.Conflict) HttpClientErrorException
                .create(HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], null);
        when(tuitionServiceClient.markPaid(any(UUID.class), eq(txId))).thenThrow(conflict);

        assertThatThrownBy(() -> paymentService.verifyOtpAndPay(txId, realOtp, userId))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Học phí đã được người khác thanh toán");

        // Tiền đã trừ phải được hoàn lại đúng bằng số đã trừ.
        ArgumentCaptor<BigDecimal> debited = ArgumentCaptor.forClass(BigDecimal.class);
        verify(authServiceClient).debit(eq(userId), debited.capture(), eq(txId));

        ArgumentCaptor<BigDecimal> refunded = ArgumentCaptor.forClass(BigDecimal.class);
        verify(authServiceClient).credit(eq(userId), refunded.capture(), eq(txId));

        assertThat(refunded.getValue()).isEqualByComparingTo(debited.getValue());
        assertThat(refunded.getValue()).isEqualByComparingTo(AMOUNT);

        Transaction failed = reload(txId);
        assertThat(failed.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(failed.getErrorMessage()).isEqualTo("Học phí đã được người khác thanh toán");
        assertThat(otpKeyExists(txId)).isFalse();
    }

    // ------------------------------------------------------------------ (e)

    @Test
    @DisplayName("verify-otp lại trên giao dịch đã SUCCESS: idempotent, không debit lần hai")
    void verifyOtpLai_trenGiaoDichSuccess_laIdempotent() {
        UUID userId = UUID.randomUUID();
        PaymentInitResponse init = initiate(userId, "52100005");
        UUID txId = init.getTransactionId();
        String realOtp = readOtp(txId);

        when(authServiceClient.debit(eq(userId), any(BigDecimal.class), eq(txId)))
                .thenReturn(new BalanceResponse(userId, BALANCE.subtract(AMOUNT)));
        when(tuitionServiceClient.markPaid(any(UUID.class), eq(txId))).thenReturn(null);

        paymentService.verifyOtpAndPay(txId, realOtp, userId);
        assertThat(reload(txId).getStatus()).isEqualTo(TransactionStatus.SUCCESS);

        PaymentSuccessResponse replay = paymentService.verifyOtpAndPay(txId, realOtp, userId);

        assertThat(replay.getMessage()).isEqualTo("Giao dịch đã được xử lý trước đó");
        assertThat(replay.getStatus()).isEqualTo("SUCCESS");
        assertThat(replay.getTransactionId()).isEqualTo(txId);

        verify(authServiceClient, times(1)).debit(eq(userId), any(BigDecimal.class), eq(txId));
        verify(tuitionServiceClient, times(1)).markPaid(any(UUID.class), eq(txId));
    }

    // ------------------------------------------------------------------ (f)

    @Test
    @DisplayName("initiate khi không còn khoản học phí chưa đóng: TuitionNotFoundException, không tạo giao dịch")
    void initiate_khongCoKhoanHocPhi_thiNotFound() {
        UUID userId = UUID.randomUUID();
        when(tuitionServiceClient.getTuitionByMssv("52100006")).thenReturn(null);

        assertThatThrownBy(() -> paymentService.initiatePayment("52100006", userId))
                .isInstanceOf(TuitionNotFoundException.class);

        assertThat(transactionRepository.count()).isZero();
        verify(authServiceClient, never()).getUserInfo(any());
    }

    // ------------------------------------------------------------------ (g)

    @Test
    @DisplayName("OTP được giữ chỗ theo giá trị: hai giao dịch đang hiệu lực không trùng mã, chốt xong thì nhả")
    void otpDangHieuLuc_khongTrungVaDuocNhaKhiChot() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID txA = initiate(userA, "52100007").getTransactionId();
        UUID txB = initiate(userB, "52100008").getTransactionId();
        String otpA = readOtp(txA);
        String otpB = readOtp(txB);

        assertThat(otpA).isNotEqualTo(otpB);
        assertThat(redisTemplate.opsForValue().get("otp:active:" + otpA)).isEqualTo(txA.toString());
        assertThat(redisTemplate.opsForValue().get("otp:active:" + otpB)).isEqualTo(txB.toString());

        // Sai đủ 3 lần -> giao dịch A chốt FAILED, mã của A phải được nhả, mã của B giữ nguyên.
        String wrong = wrongOtpFor(otpA).equals(otpB) ? "222222" : wrongOtpFor(otpA);
        for (int i = 0; i < 3; i++) {
            catchThrowableOfType(() -> paymentService.verifyOtpAndPay(txA, wrong, userA), InvalidOtpException.class);
        }
        assertThat(reload(txA).getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(redisTemplate.hasKey("otp:active:" + otpA)).isFalse();
        assertThat(redisTemplate.opsForValue().get("otp:active:" + otpB)).isEqualTo(txB.toString());
    }

    // ------------------------------------------------------------------ (h)

    @Test
    @DisplayName("initiate gặp giao dịch PROCESSING đã hết OTP: chặn lại, không đóng nhầm giao dịch đó")
    void initiate_gapGiaoDichProcessing_thiChanVaGiuNguyen() {
        TuitionInfo tuition = unpaidTuition("52100009", AMOUNT);
        Transaction stuck = new Transaction();
        stuck.setUserId(UUID.randomUUID());
        stuck.setTuitionId(tuition.getId());
        stuck.setAmount(AMOUNT);
        stuck.setStatus(TransactionStatus.PROCESSING);
        stuck = transactionRepository.save(stuck);

        UUID userId = UUID.randomUUID();
        when(tuitionServiceClient.getTuitionByMssv("52100009")).thenReturn(tuition);
        when(authServiceClient.getUserInfo(userId)).thenReturn(userWithBalance(userId, BALANCE));

        assertThatThrownBy(() -> paymentService.initiatePayment("52100009", userId))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("chờ đối soát");

        assertThat(reload(stuck.getId()).getStatus()).isEqualTo(TransactionStatus.PROCESSING);
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ (i)

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Lịch sử trả thời gian kèm offset múi giờ, client không hiểu nhầm thành giờ máy")
    void lichSu_thoiGianCoOffset() throws Exception {
        UUID userId = UUID.randomUUID();
        initiate(userId, "52100010");

        List<TransactionHistoryItem> history = paymentService.getTransactionHistory(userId);
        String json = objectMapper.writeValueAsString(history.get(0));

        assertThat(json).containsPattern("\"createdAt\":\"[^\"]+(Z|[+-][0-9]{2}:[0-9]{2})\"");
        assertThat(json).containsPattern("\"updatedAt\":\"[^\"]+(Z|[+-][0-9]{2}:[0-9]{2})\"");
    }

    // ------------------------------------------------------------------ (j)

    @Test
    @DisplayName("initiate lại khi OTP còn hạn: trả đúng giao dịch cũ kèm hạn OTP thật, không gia hạn thêm")
    void initiateLai_traHanOtpThat() {
        UUID userId = UUID.randomUUID();
        // Cùng một khoản học phí cho cả hai lần (helper initiate() sinh tuitionId mới mỗi lần gọi).
        when(tuitionServiceClient.getTuitionByMssv("52100011")).thenReturn(unpaidTuition("52100011", AMOUNT));
        when(authServiceClient.getUserInfo(userId)).thenReturn(userWithBalance(userId, BALANCE));
        PaymentInitResponse first = paymentService.initiatePayment("52100011", userId);
        assertThat(first.getOtpExpiresAt()).isNotNull();

        // Rút TTL còn 60 giây để thấy rõ response lần hai không cộng lại 5 phút.
        redisTemplate.expire(OTP_PREFIX + first.getTransactionId(), 60, java.util.concurrent.TimeUnit.SECONDS);
        PaymentInitResponse again = paymentService.initiatePayment("52100011", userId);

        assertThat(again.getTransactionId()).isEqualTo(first.getTransactionId());
        assertThat(again.getOtpExpiresAt()).isBefore(java.time.OffsetDateTime.now().plusSeconds(65));
        assertThat(again.getOtpExpiresAt()).isAfter(java.time.OffsetDateTime.now().plusSeconds(50));
    }
}
