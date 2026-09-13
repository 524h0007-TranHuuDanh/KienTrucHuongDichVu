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
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

import com.tdtu.ibanking.payment.dto.BalanceResponse;
import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.dto.PaymentSuccessResponse;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.exception.InsufficientBalanceException;
import com.tdtu.ibanking.payment.exception.InvalidOtpException;
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

        // Tiền đã trừ phải được hoàn lại ĐÚNG BẰNG số đã trừ.
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
}
