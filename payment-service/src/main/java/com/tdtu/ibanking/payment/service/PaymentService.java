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

import java.util.Map;

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
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public PaymentInitResponse initiatePayment(String mssv, UUID userId) {
        // Mới chỉ xem còn lượt hay không; lượt được trừ ở cuối, sau khi OTP đã gửi đi.
        if (!rateLimiterService.hasOtpQuota(userId)) {
            long retryAfter = rateLimiterService.getOtpRequestRetryAfterSeconds(userId);
            throw new RateLimitExceededException("Bạn đã gửi quá nhiều yêu cầu OTP. Vui lòng thử lại sau.", retryAfter);
        }

        TuitionInfo tuitionInfo = tuitionServiceClient.getTuitionByMssv(mssv);
        if (tuitionInfo == null) {
            throw new RuntimeException("Không tìm thấy khoản học phí chưa đóng cho MSSV: " + mssv);
        }
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

        // Một khoản học phí chỉ nên có một giao dịch đang chạy. Nếu giao dịch cũ vẫn
        // còn OTP hiệu lực thì nhường nó; nếu OTP đã hết hạn thì khai tử để đi tiếp.
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
        transaction.setMssv(tuitionInfo.getMssv());
        transaction.setStudentName(tuitionInfo.getStudentName());
        transaction.setAmount(tuitionInfo.getAmount());
        transaction.setStatus(TransactionStatus.PENDING);
        transaction = transactionRepository.save(transaction);

        String otp = String.format("%06d", SECURE_RANDOM.nextInt(1000000));
        redisTemplate.opsForValue().set(OTP_PREFIX + transaction.getId(), otp, OTP_TTL_MINUTES, TimeUnit.MINUTES);

        // Gửi hỏng thì OTP vừa lưu thành rác và giao dịch không bao giờ xác thực được,
        // nên dọn luôn thay vì để nó treo PENDING hết 5 phút.
        try {
            EmailMessage email = new EmailMessage(
                    userInfo.getEmail(),
                    "OTP",
                    Map.of(
                        "otp", otp,
                        "amount", tuitionInfo.getAmount().toPlainString(),
                        "expiryMinutes", String.valueOf(OTP_TTL_MINUTES),
                        "studentName", tuitionInfo.getStudentName() != null ? tuitionInfo.getStudentName() : tuitionInfo.getMssv()
                    )
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

        // Trừ lượt ở đây, không phải lúc vào hàm: RabbitMQ chết không nên ăn mất
        // hạn mức của người dùng.
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
                // Không đặt leaseTime: saga gọi qua 2 service, đặt hạn cứng là có ngày
                // khoá nhả giữa chừng. Bỏ trống thì watchdog của Redisson tự gia hạn
                // cho tới khi unlock() ở finally.
                locked = accountLock.tryLock(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceBusyException("Giao dịch bị gián đoạn, vui lòng thử lại");
            }
            if (!locked) {
                throw new ServiceBusyException("Tài khoản đang được xử lý bởi một giao dịch khác");
            }

            // Bản preCheck đọc trước khi có khoá nên có thể đã cũ — đọc lại.
            Transaction transaction = transactionRepository.findById(transactionId)
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId));

            if (transaction.getStatus() == TransactionStatus.SUCCESS) {
                return successResponse(transaction, null, "Giao dịch đã được xử lý trước đó");
            }
            // FAILED là trạng thái cuối — có thể đã hoàn tiền rồi, chạy lại là trừ hai lần.
            if (transaction.getStatus() == TransactionStatus.FAILED) {
                throw new InsufficientBalanceException("Giao dịch này đã thất bại. Vui lòng khởi tạo giao dịch mới.");
            }

            if (!rateLimiterService.canAttemptOtp(transactionId, userId)) {
                long retryAfter = rateLimiterService.getOtpAttemptRetryAfterSeconds(transactionId, userId);
                // Phòng client cũ/gọi lại: giao dịch này không còn cách nào verify được nữa
                // (OTP hết hạn 5 phút trước khi hạn mức user-level 1 giờ được gỡ) -> khai tử luôn,
                // đừng để lại PENDING chờ mãi trong lịch sử.
                String failMsg = "Bạn đã thử OTP quá nhiều lần. Vui lòng tạo lại giao dịch.";
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setErrorMessage(failMsg);
                transactionRepository.save(transaction);
                redisTemplate.delete(OTP_PREFIX + transactionId);
                throw new RateLimitExceededException(failMsg, retryAfter);
            }

            String storedOtp = (String) redisTemplate.opsForValue().get(OTP_PREFIX + transactionId);
            if (storedOtp == null || !constantTimeEquals(storedOtp, otp)) {
                rateLimiterService.recordFailedAttempt(transactionId, userId);
                int remaining = rateLimiterService.getRemainingAttempts(transactionId);
                if (remaining <= 0) {
                    // Hết lượt ngay tại lần sai này -> khai tử ngay, đừng đợi lần bấm kế tiếp
                    // (nếu không, initiate() sau đó vẫn thấy OTP key còn TTL và trả lại đúng
                    // giao dịch chết này, làm user kẹt tới 5 phút).
                    transaction.setStatus(TransactionStatus.FAILED);
                    transaction.setErrorMessage("Nhập sai OTP quá số lần cho phép");
                    transactionRepository.save(transaction);
                    redisTemplate.delete(OTP_PREFIX + transactionId);
                }
                throw new InvalidOtpException("OTP không hợp lệ hoặc đã hết hạn", remaining);
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

        // Giao dịch đã chốt: OTP không còn giá trị, và lần sai trước đó không nên
        // tính vào hạn mức của người dùng nữa.
        redisTemplate.delete(OTP_PREFIX + transactionId);
        rateLimiterService.clearAttempts(transactionId);
        rateLimiterService.clearUserFails(userId);

        sendSuccessEmail(userId, transaction, debitResult.getBalance());
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
                // Sai internal key là lỗi cấu hình, thử lại lần nữa cũng vẫn 403.
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
        // Mọi nhánh hỏng đều đi qua đây, nên dọn OTP một chỗ là đủ.
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

    private void sendSuccessEmail(UUID userId, Transaction transaction, java.math.BigDecimal balanceAfter) {
        try {
            UserInfo userInfo = authServiceClient.getUserInfo(userId);
            if (userInfo == null || userInfo.getEmail() == null) return;
            EmailMessage confirmEmail = new EmailMessage(
                    userInfo.getEmail(),
                    "PAYMENT_SUCCESS",
                    Map.of(
                            "amount", transaction.getAmount().toPlainString(),
                            "balance", balanceAfter != null ? balanceAfter.toPlainString() : "0",
                            "transactionId", transaction.getId().toString(),
                            "studentName", transaction.getStudentName() != null ? transaction.getStudentName() : "sinh viên"
                    )
            );
            rabbitTemplate.convertAndSend("email_queue", confirmEmail);
        } catch (Exception e) {
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

    public List<TransactionHistoryItem> getTransactionHistory(UUID userId) {
        return transactionRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(t -> new TransactionHistoryItem(
                        t.getId(), t.getTuitionId(), t.getMssv(), t.getStudentName(), t.getAmount(), t.getStatus(),
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