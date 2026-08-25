package com.tdtu.ibanking.payment.service;

import com.tdtu.ibanking.payment.client.AuthServiceClient;
import com.tdtu.ibanking.payment.client.TuitionServiceClient;
import com.tdtu.ibanking.payment.dto.*;
import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import com.tdtu.ibanking.payment.entity.User;
import com.tdtu.ibanking.payment.entity.Tuition;
import com.tdtu.ibanking.payment.repository.TransactionRepository;
import com.tdtu.ibanking.payment.repository.UserRepository;
import com.tdtu.ibanking.payment.repository.TuitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentService {
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final TuitionRepository tuitionRepository;
    private final AuthServiceClient authServiceClient;
    private final TuitionServiceClient tuitionServiceClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final RedissonClient redissonClient;
    private final RateLimiterService rateLimiterService;

    private static final String OTP_PREFIX = "otp:";
    private static final int OTP_TTL_MINUTES = 5;

    public PaymentInitResponse initiatePayment(String mssv, UUID userId) {
        if (!rateLimiterService.canRequestOtp(userId)) {
            throw new RuntimeException("Bạn đã gửi quá nhiều yêu cầu OTP. Vui lòng thử lại sau.");
        }

        TuitionInfo tuitionInfo = tuitionServiceClient.getTuitionByMssv(mssv);
        if (tuitionInfo == null) {
            throw new RuntimeException("Tuition not found for MSSV: " + mssv);
        }
        if (tuitionInfo.getPaid()) {
            throw new RuntimeException("Tuition already paid");
        }

        UserInfo userInfo = authServiceClient.getUserInfo(userId);
        if (userInfo == null) {
            throw new RuntimeException("User not found");
        }

        if (userInfo.getBalance().compareTo(tuitionInfo.getAmount()) < 0) {
            throw new RuntimeException("Insufficient balance");
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

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public String verifyOtpAndPay(UUID transactionId, String otp, UUID userId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found"));
        if (!transaction.getUserId().equals(userId)) {
            throw new RuntimeException("You don't have permission");
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
        RLock tuitionLock = redissonClient.getLock("lock:tuition:" + transaction.getTuitionId());

        try {
            if (!accountLock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Account is being processed");
            }
            if (!tuitionLock.tryLock(5, 10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Tuition is being processed");
            }

            User user = userRepository.findByIdForUpdate(transaction.getUserId());
            Tuition tuition = tuitionRepository.findByIdForUpdate(transaction.getTuitionId());

            if (user.getBalance().compareTo(transaction.getAmount()) < 0) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setErrorMessage("Insufficient balance");
                transactionRepository.save(transaction);
                throw new RuntimeException("Insufficient balance");
            }

            if (tuition.getPaid()) {
                transaction.setStatus(TransactionStatus.FAILED);
                transaction.setErrorMessage("Tuition already paid");
                transactionRepository.save(transaction);
                throw new RuntimeException("Tuition already paid");
            }

            user.setBalance(user.getBalance().subtract(transaction.getAmount()));
            userRepository.save(user);

            tuition.setPaid(true);
            tuition.setPaidAt(LocalDateTime.now());
            tuition.setTransactionId(transaction.getId());
            tuitionRepository.save(tuition);

            transaction.setStatus(TransactionStatus.SUCCESS);
            transactionRepository.save(transaction);

            redisTemplate.delete(OTP_PREFIX + transactionId);
            rateLimiterService.clearAttempts(transactionId);

            UserInfo userInfo = authServiceClient.getUserInfo(transaction.getUserId());
            EmailMessage confirmEmail = new EmailMessage(
                    userInfo.getEmail(),
                    "Thanh toán thành công",
                    "Bạn đã thanh toán thành công số tiền " + transaction.getAmount() + " VND."
            );
            rabbitTemplate.convertAndSend("email_queue", confirmEmail);

            log.info("Payment successful for transaction {}", transactionId);
            return "Payment successful";

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Lock interrupted", e);
        } finally {
            if (accountLock.isHeldByCurrentThread()) accountLock.unlock();
            if (tuitionLock.isHeldByCurrentThread()) tuitionLock.unlock();
        }
    }

    private String maskEmail(String email) {
        if (email == null || email.length() < 4) return "***";
        int atIndex = email.indexOf('@');
        if (atIndex < 3) return "***" + email.substring(atIndex);
        return email.substring(0, 2) + "*****" + email.substring(atIndex);
    }
}