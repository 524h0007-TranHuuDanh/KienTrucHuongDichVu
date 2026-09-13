package com.tdtu.ibanking.payment.config;

import com.tdtu.ibanking.payment.exception.InsufficientBalanceException;
import com.tdtu.ibanking.payment.exception.InvalidOtpException;
import com.tdtu.ibanking.payment.exception.RateLimitExceededException;
import com.tdtu.ibanking.payment.exception.ServiceBusyException;
import com.tdtu.ibanking.payment.exception.TransactionNotFoundException;
import com.tdtu.ibanking.payment.exception.UnauthorizedTransactionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
//sửa cho P-11: mỗi loại lỗi trả đúng mã HTTP
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<Map<String, String>> handle(InsufficientBalanceException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handle(TransactionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(UnauthorizedTransactionException.class)
    public ResponseEntity<Map<String, String>> handle(UnauthorizedTransactionException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handle(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(Map.of("message", e.getMessage(), "retryAfterSeconds", e.getRetryAfterSeconds()));
    }

    @ExceptionHandler(InvalidOtpException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidOtpException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("message", e.getMessage(), "remainingAttempts", e.getRemainingAttempts()));
    }

    @ExceptionHandler(ServiceBusyException.class)
    public ResponseEntity<Map<String, String>> handle(ServiceBusyException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
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
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Đã có lỗi xảy ra, vui lòng thử lại sau"));
    }
}