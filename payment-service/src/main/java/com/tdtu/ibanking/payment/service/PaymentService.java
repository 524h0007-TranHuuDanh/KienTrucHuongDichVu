package com.tdtu.ibanking.payment.service;

import com.tdtu.ibanking.payment.client.AuthServiceClient;
import com.tdtu.ibanking.payment.client.TuitionServiceClient;
import com.tdtu.ibanking.payment.dto.*;
import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.exception.*;
import com.tdtu.ibanking.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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
    private static final SecureRandom SECURE_RANDOM = new SecureRandom(); // P-08

    public PaymentInitResponse initiatePayment(String mssv, UUID userId) {
        // P-18: chỉ CHECK quota ở đây, KHÔNG trừ
        if (!rateLimiterService.hasOtpQuota(userId)) {
            throw new RateLimitExceededException("Bạn đã gửi quá nhiều yêu cầu OTP. Vui lòng thử lại sau.");
        }

        TuitionInfo tuitionInfo = tuitionServiceClient.getTuitionByMssv(mssv);
        if (tuitionInfo == null) {
            throw new RuntimeException("Không tìm thấy khoản học phí chưa đóng cho MSSV: " + mssv);
        }
        // P-16: check null tường minh thay vì unbox thẳng
        if (Boolean.TRUE.equals(tuitionInfo.getPaid())) {
            throw new InsufficientBalanceException("Khoản học phí này đã được đóng");
        }

        UserInfo userInfo = authServiceClient.getUserInfo(userId);
        if (userInfo == null) {
            throw new RuntimeException("Không tìm thấy tài khoản người dùng");
        }
        if (userInfo.getBalance() == null || tuitionInfo.getAmount() == null) {
            throw new ServiceBusyException("Không đọc được thông tin học phí/số dư, vui lòng thử lại");
        }
        if (userInfo.getBalance().compareTo(tuitionInfo.getAmount()) < 0) {
            throw new InsufficientBalanceException("Số dư không đủ để thanh toán");
        }

        // P-17: kiểm tra giao dịch PENDING/PROCESSING trùng cho cùng khoản học phí
        Optional<Transaction> existingOpt = transactionRepository
                .findFirstByTuitionIdAndStatusInOrderByCreatedAtDesc(
                        tuitionInfo.getId(), List.of(TransactionStatus.PENDING, TransactionStatus.PROCESSING));

        if (existingOpt.isPresent()) {
            Transaction existing = existingOpt.get();
            boolean otpStillValid = Boolean.TRUE.equals(redisTemplate.hasKey(OTP_PREFIX + existing.getId()));

            if (otpStillValid && existing.getUserId().equals(userId)) {
                return new PaymentInitResponse(existing.getId(), existing.getAmount(), userInfo.getBalance());
            }
            if (otpStillValid) {
                throw new InsufficientBalanceException(
                        "Khoản học phí này đang được người khác xử lý thanh toán, vui lòng thử lại sau ít phút");
            }
            existing.setStatus(TransactionStatus.FAILED);
            existing.setErrorMessage("Hết hạn OTP, không xác nhận trong thời gian quy định");
            transactionRepository.save(existing);
        }

        Transaction transaction = new Transaction();
        transaction.setUserId(userId);
        transaction.setTuitionId(tuitionInfo.getId());
        transaction.setAmount(tuitionInfo.getAmount());
        transaction.setStatus(TransactionStatus.PENDING);
        transaction = transactionRepository.save(transaction);

        // P-08: SecureRandom + đúng khoảng 000000-999999
        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1000000));
        redisTemplate.opsForValue().set(OTP_PREFIX + transaction.getId(), otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        // P-15 (phần initiate): bọc gửi email, dọn dẹp nếu lỗi
        try {
            EmailMessage email = new EmailMessage(
                    userInfo.getEmail(),
                    "Mã OTP xác thực thanh toán",
                    "Mã OTP của bạn là: " + otp + "\nCó hiệu lực trong " + OTP_TTL_MINUTES + " phút."
            );
            rabbitTemplate.convertAndSend("email_queue", email);
        } catch (Exception e) {
            log.error("Không gửi được OTP cho transaction {}: {}", transaction.getId(), e.getMessage());
            redisTemplate.delete(OTP_PREFIX + transaction.getId());
            transaction.setStatus(TransactionStatus.FAILED);
            transaction.setErrorMessage("Không gửi được mã OTP");
            transactionRepository.save(transaction);
            throw new ServiceBusyException("Không gửi được mã OTP, vui lòng thử lại");
        }

        // P-18: chỉ TRỪ quota SAU KHI gửi OTP thành công
        rateLimiterService.consumeOtpQuota(userId);

        log.info("OTP sent to {} for transaction {}", maskEmail(userInfo.getEmail()), transaction.getId());

        return new PaymentInitResponse(transaction.getId(), transaction.getAmount(), userInfo.getBalance());
    }

    public PaymentSuccessResponse verifyOtpAndPay(UUID transactionId, String otp, UUID userId) {
        // Đọc lần 1: chỉ để xác thực quyền sở hữu + tồn tại
        Transaction preCheck = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        if (!preCheck.getUserId().equals(userId)) {
            throw new UnauthorizedTransactionException();
        }

        RLock accountLock = redissonClient.getLock("lock:account:" + preCheck.getUserId());
        boolean locked = false;
        try {
            try {
                // P-04: bỏ leaseTime cố định -> Redisson watchdog tự gia hạn tới khi unlock()
                locked = accountLock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceBusyException("Giao dịch bị gián đoạn, vui lòng thử lại");
            }
            if (!locked) {
                throw new ServiceBusyException("Tài khoản đang được xử lý bởi một giao dịch khác");
            }

            // P-02: đọc lại từ DB SAU khi giành khoá — bắt buộc
            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));

            if (transaction.getStatus() == TransactionStatus.SUCCESS) {
                return successResponse(transaction, null, "Giao dịch đã được xử lý trước đó");
            }
            // P-01: chặn hẳn giao dịch FAILED, không cho chạy lại
            if (transaction.getStatus() == TransactionStatus.FAILED) {
                throw new InsufficientBalanceException("Giao dịch này đã thất bại. Vui lòng khởi tạo giao dịch mới.");
            }

            if (!rateLimiterService.canAttemptOtp(transactionId, userId)) {
                throw new RateLimitExceededException("Bạn đã thử OTP quá nhiều lần. Vui lòng tạo lại giao dịch.");
            }

            String storedOtp = (String) redisTemplate.opsForValue().get(OTP_PREFIX + transactionId);
            // P-21: so sánh constant-time
            if (storedOtp == null || !constantTimeEquals(storedOtp, otp)) {
                rateLimiterService.recordFailedAttempt(transactionId, userId);
                throw new InsufficientBalanceException("OTP không hợp lệ hoặc đã hết hạn");
            }

            return runSaga(transaction);
        } finally {
            if (locked && accountLock.isHeldByCurrentThread()) {
                accountLock.unlock();
            }
        }
    }

    private PaymentSuccessResponse runSaga(Transaction transaction) {
        UUID transactionId = transaction.getId();
        UUID userId = transaction.getUserId();

        transaction.setStatus(TransactionStatus.PROCESSING);
        transactionRepository.save(transaction);

        BalanceResponse debitResult = doDebit(transaction);
        if (debitResult == null) {
            throw new ServiceBusyException("Hệ thống đang bận, giao dịch của bạn đang được xử lý. Vui lòng kiểm tra lại sau.");
        }

        boolean success = doMarkPaidWithSaga(transaction);
        if (!success) {
            if (transaction.getStatus() == TransactionStatus.FAILED) {
                throw new InsufficientBalanceException(
                        transaction.getErrorMessage() != null ? transaction.getErrorMessage() : "Thanh toán thất bại");
            }
            throw new ServiceBusyException("Hệ thống đang bận, giao dịch của bạn đang được xử lý. Vui lòng kiểm tra lại sau.");
        }

        // P-01: dọn dẹp OTP + rate-limit
        redisTemplate.delete(OTP_PREFIX + transactionId);
        rateLimiterService.clearAttempts(transactionId);
        rateLimiterService.clearUserFails(userId);   // P-19

        sendSuccessEmail(userId, transaction);

        log.info("Payment successful for transaction {}", transactionId);
        return successResponse(transaction, debitResult.getBalance(), "Thanh toán thành công");
    }

    private BalanceResponse doDebit(Transaction transaction) {
        UUID userId = transaction.getUserId();
        UUID transactionId = transaction.getId();

        for (int attempt = 1; attempt <= MAX_NETWORK_RETRIES + 1; attempt++) {
            try {
                return authServiceClient.debit(userId, transaction.getAmount(), transactionId);
            } catch (HttpClientErrorException.Conflict e) {
                failTransaction(transaction, "Số dư không đủ");
                throw new InsufficientBalanceException("Số dư không đủ");
            } catch (HttpClientErrorException.NotFound e) {
                failTransaction(transaction, "Không tìm thấy tài khoản");
                throw new TransactionNotFoundException(transactionId);
            } catch (HttpClientErrorException.Forbidden e) {
                // P-05: lỗi cấu hình internal-key -> KHÔNG thử lại
                log.error("SAI CẤU HÌNH: auth-service từ chối internal API key cho transaction {}", transactionId);
                failTransaction(transaction, "Lỗi cấu hình hệ thống");
                throw new ServiceBusyException("Hệ thống gặp sự cố, vui lòng thử lại sau");
            } catch (HttpClientErrorException e) {
                log.error("Lỗi 4xx không mong đợi ({}) khi debit cho transaction {}: {}",
                        e.getStatusCode(), transactionId, e.getResponseBodyAsString());
                failTransaction(transaction, "Thanh toán thất bại, vui lòng thử lại");
                throw new ServiceBusyException("Thanh toán thất bại, vui lòng thử lại");
            } catch (ResourceAccessException | HttpServerErrorException e) {
                log.warn("debit() lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        log.error("debit() thất bại sau {} lần thử cho transaction {} - CẦN ĐỐI SOÁT TAY",
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
            } catch (HttpClientErrorException.Forbidden e) {
                log.error("SAI CẤU HÌNH: tuition-service từ chối internal API key cho transaction {}", transactionId);
                refundAndFail(transaction, "Lỗi cấu hình hệ thống");
                return false;
            } catch (HttpClientErrorException e) {
                log.error("Lỗi 4xx không mong đợi ({}) khi markPaid cho transaction {}: {}",
                        e.getStatusCode(), transactionId, e.getResponseBodyAsString());
                refundAndFail(transaction, "Thanh toán thất bại, vui lòng thử lại");
                return false;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                log.warn("markPaid() lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        TuitionDetailInfo detail;
        try {
            detail = tuitionServiceClient.getTuitionById(tuitionId);
        } catch (Exception e) {
            detail = null;
        }

        if (detail == null) {
            log.error("markPaid() timeout và không đọc lại được trạng thái tuition {} cho transaction {} " +
                    "- CẦN ĐỐI SOÁT TAY", tuitionId, transactionId);
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
        // P-01: dọn dẹp OTP + rate-limit ở MỌI nhánh thất bại
        redisTemplate.delete(OTP_PREFIX + transaction.getId());
        rateLimiterService.clearAttempts(transaction.getId());
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
            } catch (HttpClientErrorException e) {
                log.error("Lỗi 4xx không mong đợi ({}) khi hoàn tiền cho transaction {}: {}",
                        e.getStatusCode(), transactionId, e.getResponseBodyAsString());
                failTransaction(transaction, failMessage + " (CẢNH BÁO: hoàn tiền tự động thất bại, cần đối soát tay)");
                return;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                log.warn("credit() hoàn tiền lần thử {}/{} thất bại cho transaction {}: {}",
                        attempt, MAX_NETWORK_RETRIES + 1, transactionId, e.getMessage());
            }
        }

        log.error("credit() hoàn tiền thất bại sau {} lần thử cho transaction {} - tiền đã trừ nhưng CHƯA hoàn được, " +
                "CẦN ĐỐI SOÁT TAY NGAY", MAX_NETWORK_RETRIES + 1, transactionId);
    }

    private void sendSuccessEmail(UUID userId, Transaction transaction) {
        try {
            UserInfo userInfo = authServiceClient.getUserInfo(userId);
            if (userInfo == null || userInfo.getEmail() == null) return;
            EmailMessage confirmEmail = new EmailMessage(
                    userInfo.getEmail(),
                    "Thanh toán thành công",
                    "Bạn đã thanh toán thành công số tiền " + transaction.getAmount() + " VND."
            );
            rabbitTemplate.convertAndSend("email_queue", confirmEmail);
        } catch (Exception e) {   // P-15: bắt MỌI lỗi, không riêng lỗi HTTP
            log.warn("Không gửi được email xác nhận cho transaction {}: {}", transaction.getId(), e.getMessage());
        }
    }

    private PaymentSuccessResponse successResponse(Transaction transaction, java.math.BigDecimal balance, String message) {
        return new PaymentSuccessResponse(
                transaction.getId(), "SUCCESS", transaction.getAmount(),
                balance, LocalDateTime.now(), message);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    // API lịch sử giao dịch (đặc tả Mục 1: "Lịch sử các giao dịch đã thực hiện")
    public List<TransactionHistoryItem> getTransactionHistory(UUID userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new TransactionHistoryItem(
                        t.getId(), t.getTuitionId(), t.getAmount(), t.getStatus(),
                        t.getErrorMessage(), t.getCreatedAt(), t.getUpdatedAt()))
                .toList();
    }

    private String maskEmail(String email) {
        if (email == null || email.length() < 4) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex < 3) return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "*****" + email.substring(atIndex);
    }

    
}