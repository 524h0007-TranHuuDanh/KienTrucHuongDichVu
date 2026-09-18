package com.tdtu.ibanking.payment.controller;

import com.tdtu.ibanking.payment.dto.OtpVerifyRequest;
import com.tdtu.ibanking.payment.dto.PaymentInitRequest;
import com.tdtu.ibanking.payment.dto.PaymentInitResponse;
import com.tdtu.ibanking.payment.dto.PaymentSuccessResponse;
import com.tdtu.ibanking.payment.dto.TransactionHistoryItem;
import com.tdtu.ibanking.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payment", description = "Thanh toán học phí và xác thực OTP")
public class PaymentController {
    private final PaymentService paymentService;

    @PostMapping("/initiate")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Khởi tạo giao dịch thanh toán học phí",
            description = "Tạo giao dịch ở trạng thái chờ cho khoản học phí của MSSV được gửi lên, "
                    + "kiểm tra số dư của người dùng đang đăng nhập và gửi mã OTP qua email. "
                    + "userId được lấy từ JWT, không nhận từ phía client."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Khởi tạo giao dịch thành công, OTP đã được gửi",
                    content = @Content(schema = @Schema(implementation = PaymentInitResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (MSSV không đúng định dạng, VD: 524H0001)",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "401", description = "Thiếu token hoặc token không hợp lệ",
                    content = @Content),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy học phí hoặc giao dịch tương ứng",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "409", description = "Số dư không đủ để thanh toán học phí",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "429", description = "Vượt quá giới hạn số lần yêu cầu, vui lòng thử lại sau",
                    headers = @Header(name = "Retry-After", description = "Số giây phải chờ trước khi thử lại",
                            schema = @Schema(type = "integer")),
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "503", description = "Hệ thống đang bận, vui lòng thử lại sau",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "500", description = "Lỗi không mong đợi từ hệ thống",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<PaymentInitResponse> initiatePayment(
            @Valid @RequestBody PaymentInitRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        PaymentInitResponse response = paymentService.initiatePayment(request.getMssv(), userId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/verify-otp")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Xác thực OTP và hoàn tất thanh toán",
            description = "Kiểm tra mã OTP của giao dịch, trừ tiền trong tài khoản và đánh dấu học phí đã đóng. "
                    + "Giao dịch phải thuộc về người dùng đang đăng nhập (userId lấy từ JWT)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thanh toán thành công",
                    content = @Content(schema = @Schema(implementation = PaymentSuccessResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (thiếu mã giao dịch hoặc OTP phải là 6 chữ số)",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "401", description = "Thiếu token hoặc token không hợp lệ",
                    content = @Content),
            @ApiResponse(responseCode = "403", description = "Giao dịch không thuộc về người dùng hiện tại",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy giao dịch",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "409", description = "OTP sai (body kèm remainingAttempts) hoặc số dư không đủ",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "429", description = "Vượt quá giới hạn số lần yêu cầu, vui lòng thử lại sau",
                    headers = @Header(name = "Retry-After", description = "Số giây phải chờ trước khi thử lại",
                            schema = @Schema(type = "integer")),
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "503", description = "Hệ thống đang bận, vui lòng thử lại sau",
                    content = @Content(schema = @Schema(type = "object"))),
            @ApiResponse(responseCode = "500", description = "Lỗi không mong đợi từ hệ thống",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<PaymentSuccessResponse> verifyOtp(
            @Valid @RequestBody OtpVerifyRequest request,
            @Parameter(hidden = true) HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        PaymentSuccessResponse result = paymentService.verifyOtpAndPay(request.getTransactionId(), request.getOtp(), userId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/history")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            summary = "Lịch sử giao dịch của người dùng",
            description = "Trả về danh sách giao dịch thanh toán học phí của người dùng đang đăng nhập "
                    + "(userId lấy từ JWT, không nhận từ phía client)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Danh sách giao dịch",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = TransactionHistoryItem.class)))),
            @ApiResponse(responseCode = "401", description = "Thiếu token hoặc token không hợp lệ",
                    content = @Content),
            @ApiResponse(responseCode = "500", description = "Lỗi không mong đợi từ hệ thống",
                    content = @Content(schema = @Schema(type = "object")))
    })
    public ResponseEntity<List<TransactionHistoryItem>> getHistory(
            @Parameter(hidden = true) HttpServletRequest httpRequest) {
        UUID userId = (UUID) httpRequest.getAttribute("userId");
        return ResponseEntity.ok(paymentService.getTransactionHistory(userId));
    }
}
