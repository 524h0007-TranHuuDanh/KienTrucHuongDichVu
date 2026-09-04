package com.tdtu.ibanking.payment.controller;

import com.tdtu.ibanking.payment.dto.OtpVerifyRequest;
import com.tdtu.ibanking.payment.dto.PaymentInitRequest;
import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.dto.PaymentSuccessResponse;
import com.tdtu.ibanking.payment.dto.TransactionHistoryItem;
import com.tdtu.ibanking.payment.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
//sửa cho p20: verifyOtp trả JSON object
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/initiate")
    public ResponseEntity<PaymentInitResponse> initiatePayment(
            @Valid @RequestBody PaymentInitRequest request,
            HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        PaymentInitResponse response = paymentService.initiatePayment(request.getMssv(), userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<PaymentSuccessResponse> verifyOtp(
            @Valid @RequestBody OtpVerifyRequest request,
            HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        PaymentSuccessResponse result = paymentService.verifyOtpAndPay(request.getTransactionId(), request.getOtp(), userId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    public ResponseEntity<List<TransactionHistoryItem>> getHistory(HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        return ResponseEntity.ok(paymentService.getTransactionHistory(userId));
    }
}