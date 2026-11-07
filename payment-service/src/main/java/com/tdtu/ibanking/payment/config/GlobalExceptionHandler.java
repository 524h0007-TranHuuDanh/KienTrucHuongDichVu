package com.tdtu.ibanking.payment.config;

import com.tdtu.ibanking.payment.exception.ErrorCode;
import com.tdtu.ibanking.payment.exception.InsufficientBalanceException;
import com.tdtu.ibanking.payment.exception.InvalidOtpException;
import com.tdtu.ibanking.payment.exception.PaymentConflictException;
import com.tdtu.ibanking.payment.exception.RateLimitExceededException;
import com.tdtu.ibanking.payment.exception.ServiceBusyException;
import com.tdtu.ibanking.payment.exception.TransactionNotFoundException;
import com.tdtu.ibanking.payment.exception.TuitionNotFoundException;
import com.tdtu.ibanking.payment.exception.UnauthorizedTransactionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

// Mọi body lỗi nghiệp vụ có dạng { message, code } (cộng field riêng của từng ca).
// Riêng lỗi validate 400 giữ dạng map theo tên field như cũ.
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> handle(InsufficientBalanceException e) {
        return error(HttpStatus.CONFLICT, e.getMessage(), ErrorCode.INSUFFICIENT_BALANCE);
    }

    @ExceptionHandler(PaymentConflictException.class)
    public ResponseEntity<Map<String, String>> handle(PaymentConflictException e) {
        return error(HttpStatus.CONFLICT, e.getMessage(), e.getCode());
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handle(TransactionNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), ErrorCode.TRANSACTION_NOT_FOUND);
    }

    @ExceptionHandler(TuitionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handle(TuitionNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), ErrorCode.TUITION_NOT_FOUND);
    }

    @ExceptionHandler(UnauthorizedTransactionException.class)
    public ResponseEntity<Map<String, String>> handle(UnauthorizedTransactionException e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage(), ErrorCode.TRANSACTION_FORBIDDEN);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handle(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("message", e.getMessage(), "code", e.getCode(),
                        "retryAfterSeconds", e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidOtpException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage(), "code", ErrorCode.OTP_INVALID,
                        "remainingAttempts", e.getRemainingAttempts()));
    }

    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<Map<String, String>> handle(ServiceBusyException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), ErrorCode.SERVICE_BUSY);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err -> errors.put(err.getField(), err.getDefaultMessage()));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    // Bắt cuối — lỗi không lường trước (kể cả 403 sai internal-key nếu lọt tới đây) -> 500, không lộ chi tiết
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Lỗi không mong đợi", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra, vui lòng thử lại sau", ErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Map<String, String>> error(HttpStatus status, String message, String code) {
        return ResponseEntity.status(status).body(Map.of("message", message, "code", code));
    }
}
