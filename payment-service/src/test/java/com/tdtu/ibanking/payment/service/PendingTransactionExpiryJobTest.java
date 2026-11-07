package com.tdtu.ibanking.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.support.AbstractPaymentIT;

/** Giao dịch bị bỏ dở (huỷ, đóng tab, để OTP hết hạn) phải được chốt FAILED. */
class PendingTransactionExpiryJobTest extends AbstractPaymentIT {

    @Autowired
    private PendingTransactionExpiryJob job;

    /** Lưu một giao dịch rồi lùi createdAt về quá khứ (@PrePersist luôn đặt = now). */
    private Transaction saveTx(TransactionStatus status, int minutesAgo) {
        Transaction t = new Transaction();
        t.setUserId(UUID.randomUUID());
        t.setTuitionId(UUID.randomUUID());
        t.setAmount(new BigDecimal("5000000.00"));
        t.setStatus(status);
        t = transactionRepository.save(t);
        t.setCreatedAt(LocalDateTime.now().minusMinutes(minutesAgo));
        return transactionRepository.save(t);
    }

    private TransactionStatus statusOf(Transaction t) {
        return transactionRepository.findById(t.getId()).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("PENDING quá hạn và không còn OTP -> FAILED; còn mới, còn OTP hoặc PROCESSING -> giữ nguyên")
    void chiChotPendingDaHetHan() {
        Transaction stale = saveTx(TransactionStatus.PENDING, 30);
        Transaction fresh = saveTx(TransactionStatus.PENDING, 1);
        Transaction staleButOtpAlive = saveTx(TransactionStatus.PENDING, 30);
        redisTemplate.opsForValue().set(OTP_PREFIX + staleButOtpAlive.getId(), "123456", 1, TimeUnit.MINUTES);
        Transaction processing = saveTx(TransactionStatus.PROCESSING, 30);

        job.expireStalePending();

        Transaction expired = transactionRepository.findById(stale.getId()).orElseThrow();
        assertThat(expired.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(expired.getErrorMessage()).isEqualTo(PendingTransactionExpiryJob.EXPIRED_MESSAGE);

        assertThat(statusOf(fresh)).isEqualTo(TransactionStatus.PENDING);
        assertThat(statusOf(staleButOtpAlive)).isEqualTo(TransactionStatus.PENDING);
        assertThat(statusOf(processing)).isEqualTo(TransactionStatus.PROCESSING);
    }
}
