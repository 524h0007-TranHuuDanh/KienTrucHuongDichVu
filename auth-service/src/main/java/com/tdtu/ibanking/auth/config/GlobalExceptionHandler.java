package com.tdtu.ibanking.auth.config;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tdtu.ibanking.auth.exception.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpStatusCodeException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", e.getMessage()));
    }

    /**
     * account-service (qua AccountServiceClient) trả lỗi nghiệp vụ dưới dạng HTTP status
     * + body {"message": "..."} - ví dụ 409 số dư không đủ / giao dịch đã chốt / hoàn
     * tiền không hợp lệ (InsufficientBalanceException/InvalidRefundException/
     * TransactionAlreadyFinalizedException cũ giờ chỉ còn tồn tại bên account-service).
     * auth-service giữ nguyên status code + message đó khi trả về client - không cần
     * exception riêng cho từng trường hợp vì bản thân account-service đã phân loại.
     */
    @ExceptionHandler(HttpStatusCodeException.class)
    public ResponseEntity<Map<String, String>> handleAccountServiceError(HttpStatusCodeException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", extractMessage(e)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Dữ liệu không hợp lệ");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", message));
    }

    private String extractMessage(HttpStatusCodeException e) {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(e.getResponseBodyAsString());
            if (node.hasNonNull("message")) {
                return node.get("message").asText();
            }
        } catch (Exception ignored) {
            // body không phải JSON hợp lệ - rơi xuống fallback bên dưới
        }
        return e.getMessage();
    }
}
