package com.tdtu.ibanking.payment.service;

import com.tdtu.ibanking.payment.client.AuthServiceClient;
import com.tdtu.ibanking.payment.client.TuitionServiceClient;
import com.tdtu.ibanking.payment.dto.*;
import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {
    private final TransactionRepository transactionRepository;
    private final AuthServiceClient authServiceClient;
    private final TuitionServiceClient tuitionServiceClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;
    private final RateLimiterService rateLimiterService;

    private static final String OTP_PREFIX = "otp:";
    private static final int OTP_TTL_MINUTES = 5;
    private static final int MAX_NETWORK_RETRIES = 2;

    public PaymentInitResponse initiatePayment(String mssv, UUID userId) {
        if (!rateLimiterService.canRequestOtp(userId)) {
            throw new RuntimeException("Bạn đã gửi quá nhiều yêu cầu OTP. Vui lòng thử lại sau.");
        }

        TuitionInfo tuitionInfo = tuitionServiceClient.getTuitionByMssv(mssv);
        if (tuitionInfo == null) {
            throw new RuntimeException("Không tìm thấy khoản học phí chưa đóng cho MSSV: " + mssv);
        }
        if (tuitionInfo.getPaid()) {
            throw new RuntimeException("Khoản học phí này đã được đóng");
        }

        UserInfo userInfo = authServiceClient.getUserInfo(userId);
        if (userInfo == null) {
            throw new RuntimeException("Không tìm thấy tài khoản người dùng");
        }

        if (userInfo.getBalance().compareTo(tuitionInfo.getAmount()) < 0) {
            throw new RuntimeException("Số dư không đủ để thanh toán");
        }

        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        transaction.setTuitionId(tuitionInfo.getId());
        transaction.setAmount(tuitionInfo.getAmount());
        transaction.setStatus(TransactionStatus.PENDING);
        transaction = transactionRepository.save(transaction);

        String otp = String.format("%06d", new Random().nextInt(999999));
        redisTemplate.opsForValue().set(OTP_PREFIX + transaction.getId(), otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        EmailMessage email = new EmailMessage(
                userInfo.getEmail(),
                "Mã OTP xác thực thanh toán",
                "Mã OTP của bạn là: " + otp + "\nCó hiệu lực trong " + OTP_TTL_MINUTES + " phút."
        );
        rabbitTemplate.convertAndSend("email_queue", email);

        log.info("OTP sent to {} for transaction {}", maskEmail(userInfo.getEmail()), transaction.getId());

        return new PaymentInitResponse(
                transaction.getId(),
                transaction.getAmount(),
                userInfo.getBalance()
        );
    }

    public String verifyOtpAndPay(UUID transactionId, String otp, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy giao dịch"));
        if (!transaction.getUserId().equals(userId)) {
            throw new RuntimeException("Bạn không có quyền thực hiện giao dịch này");
        }

        if (transaction.getStatus() == TransactionStatus.SUCCESS) {
            return "Payment successful";
        }

        if (!rateLimiterService.canAttemptOtp(transactionId)) {
            throw new RuntimeException("Bạn đã thử OTP quá nhiều lần. Vui lòng tạo lại giao dịch.");
        }

        String storedOtp = (String) redisTemplate.opsForValue().get(OTP_PREFIX + transactionId);
        if (storedOtp == null || !storedOtp.equals(otp)) {
            rateLimiterService.recordFailedAttempt(transactionId);
            throw new RuntimeException("OTP không hợp lệ hoặc đã hết hạn");
        }

        RLock accountLock = redissonClient.getLock("lock:account:" + transaction.getUserId());
        boolean locked = false;
        try {
            try {
                locked = accountLock.tryLock(5, 30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Giao dịch bị gián đoạn, vui lòng thử lại");
            }
            if (!locked) {
                throw new RuntimeException("Tài khoản đang được xử lý bởi một giao dịch khác");
            }

            return runSaga(transaction);
        } finally {
            if (locked && accountLock.isHeldByCurrentThread()) {
                accountLock.unlock();
            }
        }
    }

    private String runSaga(Transaction transaction) {
        UUID transactionId = transaction.getId();
        UUID userId = transaction.getUserId();

        transaction.setStatus(TransactionStatus.PROCESSING);
        transactionRepository.save(transaction);

        BalanceResponse debitResult = doDebit(transaction);
        if (debitResult == null) {
            throw new RuntimeException("Hệ thống đang bận, giao dịch của bạn đang được xử lý. Vui lòng kiểm tra lại sau.");
        }

        boolean success = doMarkPaidWithSaga(transaction);
        if (!success) {
            if (transaction.getStatus() == TransactionStatus.FAILED) {
                throw new RuntimeException(
                        transaction.getErrorMessage() != null ? transaction.getErrorMessage() : "Thanh toán thất bại");
            }
            throw new RuntimeException("Hệ thống đang bận, giao dịch của bạn đang được xử lý. Vui lòng kiểm tra lại sau.");
        }

        redisTemplate.delete(OTP_PREFIX + transactionId);
        rateLimiterService.clearAttempts(transactionId);

        sendSuccessEmail(userId, transaction);

        log.info("Payment successful for transaction {}", transactionId);
        return "Payment successful";
    }

    private BalanceResponse doDebit(Transaction transaction) {
        UUID userId = transaction.getUserId();
        UUID transactionId = transaction.getId();

        for (int attempt = 1; attempt <= MAX_NETWORK_RETRIES + 1; attempt++) {
            try {
                return authServiceClient.debit(userId, transaction.getAmount(), transactionId);
            } catch (HttpClientErrorException.Conflict e) {
                failTransaction(transaction, "Số dư không đủ");
                throw new RuntimeException("Số dư không đủ");
            } catch (HttpClientErrorException.NotFound e) {
                failTransaction(transaction, "Không tìm thấy tài khoản");
                throw new RuntimeException("Không tìm thấy tài khoản");
            } catch (RestClientException e) {
                log.warn("debit() lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        log.error("debit() thất bại sau {} lần thử cho transaction {} - transaction giữ PROCESSING, cần đối soát tay",
                MAX_NETWORK_RETRIES + 1, transactionId);
        return null;
    }

    private boolean doMarkPaidWithSaga(Transaction transaction) {
        UUID tuitionId = transaction.getTuitionId();
        UUID transactionId = transaction.getId();

        for (int attempt = 1; attempt <= MAX_NETWORK_RETRIES + 1; attempt++) {
            try {
                tuitionServiceClient.markPaid(tuitionId, transactionId);
                transaction.setStatus(TransactionStatus.SUCCESS);
                transactionRepository.save(transaction);
                return true;
            } catch (HttpClientErrorException.Conflict e) {
                refundAndFail(transaction, "Học phí đã được người khác thanh toán");
                return false;
            } catch (HttpClientErrorException.NotFound e) {
                refundAndFail(transaction, "Không tìm thấy khoản học phí");
                return false;
            } catch (RestClientException e) {
                log.warn("markPaid() lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        TuitionDetailInfo detail;
        try {
            detail = tuitionServiceClient.getTuitionById(tuitionId);
        } catch (RestClientException e) {
            detail = null;
        }

        if (detail == null) {
            log.error("markPaid() timeout và không đọc lại được trạng thái tuition {} cho transaction {} " +
                    "- transaction giữ PROCESSING, cần đối soát tay", tuitionId, transactionId);
            return false;
        }

        if (transactionId.equals(detail.getTransactionId())) {
            transaction.setStatus(TransactionStatus.SUCCESS);
            transactionRepository.save(transaction);
            return true;
        }

        if (Boolean.FALSE.equals(detail.getPaid())) {
            refundAndFail(transaction, "Không thể xác nhận thanh toán học phí, đã hoàn tiền");
            return false;
        }

        refundAndFail(transaction, "Học phí đã được người khác thanh toán");
        return false;
    }

    private void failTransaction(Transaction transaction, String message) {
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setErrorMessage(message);
        transactionRepository.save(transaction);
    }

    private void refundAndFail(Transaction transaction, String failMessage) {
        UUID userId = transaction.getUserId();
        UUID transactionId = transaction.getId();

        for (int attempt = 1; attempt <= MAX_NETWORK_RETRIES + 1; attempt++) {
            try {
                authServiceClient.credit(userId, transaction.getAmount(), transactionId);
                failTransaction(transaction, failMessage);
                return;
            } catch (HttpClientErrorException.Conflict | HttpClientErrorException.NotFound e) {
                log.error("credit() hoàn tiền thất bại bất thường ({}) cho transaction {} - CẦN ĐỐI SOÁT TAY",
                        e.getStatusCode(), transactionId);
                failTransaction(transaction, failMessage + " (CẢNH BÁO: hoàn tiền tự động thất bại, cần đối soát tay)");
                return;
            } catch (RestClientException e) {
                log.warn("credit() hoàn tiền lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        log.error("credit() hoàn tiền thất bại sau {} lần thử cho transaction {} - transaction giữ PROCESSING, " +
                "tiền đã trừ nhưng CHƯA hoàn được, CẦN ĐỐI SOÁT TAY NGAY", MAX_NETWORK_RETRIES + 1, transactionId);
    }

    private void sendSuccessEmail(UUID userId, Transaction transaction) {
        try {
            UserInfo userInfo = authServiceClient.getUserInfo(userId);
            if (userInfo == null || userInfo.getEmail() == null) {
                return;
            }
            EmailMessage confirmEmail = new EmailMessage(
                    userInfo.getEmail(),
                    "Thanh toán thành công",
                    "Bạn đã thanh toán thành công số tiền " + transaction.getAmount() + " VND."
            );
            rabbitTemplate.convertAndSend("email_queue", confirmEmail);
        } catch (RestClientException e) {
            log.warn("Không gửi được email xác nhận cho transaction {}: {}", transaction.getId(), e.getMessage());
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.length() < 4) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex < 3) return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "*****" + email.substring(atIndex);
    }
}
