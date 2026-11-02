package com.tdtu.ibanking.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.dto.TuitionInfo;
import com.tdtu.ibanking.payment.dto.UserInfo;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.exception.ErrorCode;
import com.tdtu.ibanking.payment.exception.PaymentConflictException;
import com.tdtu.ibanking.payment.exception.InvalidOtpException;
import com.tdtu.ibanking.payment.support.AbstractPaymentIT;

/**
 * Nhiều người cùng initiate một khoản học phí: chỉ một người được cấp OTP.
 * Dùng starting gate như các test tranh chấp khác để các thread chạy đồng thời thật.
 */
class TuitionInitiateConcurrencyIT extends AbstractPaymentIT {

    private static final int THREADS = 8;
    private static final BigDecimal AMOUNT = new BigDecimal("5000000.00");

    @Test
    @DisplayName("8 người cùng initiate một khoản học phí -> đúng 1 giao dịch được tạo")
    void nhieuNguoiCungInitiate_chiMotNguoiDuocCapOtp() throws Exception {
        TuitionInfo tuition = unpaidTuition("52100099", AMOUNT);
        when(tuitionServiceClient.getTuitionByMssv("52100099")).thenReturn(tuition);
        when(authServiceClient.getUserInfo(any(UUID.class))).thenAnswer(inv ->
                new UserInfo(inv.getArgument(0), "student@tdtu.edu.vn", new BigDecimal("10000000.00"), 0));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger blocked = new AtomicInteger();
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < THREADS; i++) {
            UUID userId = UUID.randomUUID();
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    paymentService.initiatePayment("52100099", userId);
                    ok.incrementAndGet();
                } catch (PaymentConflictException e) {
                    if (ErrorCode.TUITION_LOCKED.equals(e.getCode())) {
                        blocked.incrementAndGet();
                    } else {
                        unexpected.add(e);
                    }
                } catch (Throwable t) {
                    unexpected.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(unexpected).isEmpty();
        assertThat(ok.get()).isEqualTo(1);
        assertThat(blocked.get()).isEqualTo(THREADS - 1);
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("Giao dịch đang giữ khoản học phí thất bại -> khoản được nhả, người khác initiate được ngay")
    void giaoDichThatBai_thiNhaKhoanHocPhi() {
        TuitionInfo tuition = unpaidTuition("52100098", AMOUNT);
        when(tuitionServiceClient.getTuitionByMssv("52100098")).thenReturn(tuition);
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        when(authServiceClient.getUserInfo(userA)).thenReturn(userWithBalance(userA, new BigDecimal("10000000.00")));
        when(authServiceClient.getUserInfo(userB)).thenReturn(userWithBalance(userB, new BigDecimal("10000000.00")));

        PaymentInitResponse a = paymentService.initiatePayment("52100098", userA);
        String wrong = wrongOtpFor(readOtp(a.getTransactionId()));
        for (int i = 0; i < 3; i++) {
            catchInvalid(() -> paymentService.verifyOtpAndPay(a.getTransactionId(), wrong, userA));
        }
        assertThat(transactionRepository.findById(a.getTransactionId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.FAILED);

        PaymentInitResponse b = paymentService.initiatePayment("52100098", userB);
        assertThat(b.getTransactionId()).isNotEqualTo(a.getTransactionId());
    }

    private void catchInvalid(Runnable r) {
        try {
            r.run();
        } catch (InvalidOtpException expected) {
            // sai OTP là đúng kịch bản
        }
    }
}
