package com.tdtu.ibanking.tuition.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.tdtu.ibanking.tuition.exception.TuitionAlreadyPaidException;
import com.tdtu.ibanking.tuition.exception.TuitionNotFoundException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(TuitionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TuitionNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(TuitionAlreadyPaidException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyPaid(TuitionAlreadyPaidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(message));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        log.warn("Optimistic locking khi mark-paid", ex);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody("Khoản học phí này vừa được một giao dịch khác xử lý, vui lòng thử lại."));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(RuntimeException ex) {
        log.error("Lỗi không lường trước ở tuition-service", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody("Có lỗi xảy ra, vui lòng thử lại sau"));
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new HashMap<>();
        body.put("message", message);
        return body;
    }
}
