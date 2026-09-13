package com.tdtu.ibanking.auth.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tdtu.ibanking.auth.dto.BalanceChangeRequest;
import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.dto.LoginRequest;
import com.tdtu.ibanking.auth.dto.LoginResponse;
import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.repository.UserRepository;
import com.tdtu.ibanking.auth.security.JwtUtils;
import com.tdtu.ibanking.auth.security.UserDetailsImpl;
import com.tdtu.ibanking.auth.service.BalanceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Đăng nhập và quản lý số dư")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceService balanceService;

    @Operation(
            summary = "Đăng nhập",
            description = "Xác thực username/password và trả về JWT dùng cho các lời gọi tiếp theo. "
                    + "Endpoint công khai, không cần token.")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Đăng nhập thành công, trả về JWT kèm thông tin tài khoản",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "401",
                    description = "Sai mật khẩu hoặc tài khoản không tồn tại: {\"error\":\"Sai mật khẩu!\"}",
                    content = @Content(mediaType = "application/json"))
    })
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));

            User user = userRepository.findByUsername(loginRequest.getUsername())
                    .orElseThrow(() -> new RuntimeException("User not found"));
            String jwt = jwtUtils.generateJwtToken(authentication, user.getId());

            return ResponseEntity.ok(new LoginResponse(jwt, user.getId(), user.getEmail(), user.getBalance()));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", "Sai mật khẩu!"));
        }
    }

    @Operation(
            summary = "Xem thông tin tài khoản",
            description = "Trả về id, email, fullName, phone và balance. Chấp nhận JWT của chính chủ "
                    + "tài khoản HOẶC khóa nội bộ X-Internal-Api-Key (lời gọi service-to-service).")
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "internalApiKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Thông tin tài khoản",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "401",
                    description = "Token không hợp lệ hoặc đã hết hạn",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403",
                    description = "Không có quyền truy cập thông tin tài khoản này",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404",
                    description = "Không tìm thấy tài khoản",
                    content = @Content(mediaType = "application/json"))
    })
    @GetMapping("/users/{userId}")
    public ResponseEntity<?> getUserInfo(@PathVariable UUID userId) {
        enforceOwnershipOrInternal(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        // sửa để đúng đặc tả (Mục 1): trả thêm "phone" - dùng HashMap vì Map.of()
        // ném NPE nếu phone null (chưa mọi user demo đều có số điện thoại)
        Map<String, Object> body = new HashMap<>();
        body.put("id", user.getId());
        body.put("email", user.getEmail());
        body.put("fullName", user.getFullName());
        body.put("phone", user.getPhone());
        body.put("balance", user.getBalance());
        return ResponseEntity.ok(body);
    }

    @Operation(
            summary = "Trừ tiền tài khoản (nội bộ)",
            description = "Chỉ dành cho lời gọi service-to-service, bắt buộc header X-Internal-Api-Key. "
                    + "Idempotent theo transactionId: gọi lại cùng transactionId chỉ trả về số dư hiện tại.")
    @SecurityRequirement(name = "internalApiKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Trừ tiền thành công, trả về số dư mới",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BalanceResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Dữ liệu không hợp lệ (amount phải lớn hơn 0, thiếu transactionId)",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403",
                    description = "Endpoint nội bộ, không được gọi trực tiếp (thiếu hoặc sai khóa nội bộ)",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404",
                    description = "Người dùng không tồn tại",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "409",
                    description = "Số dư không đủ hoặc giao dịch đã được chốt trước đó",
                    content = @Content(mediaType = "application/json"))
    })
    @PostMapping("/users/{id}/debit")
    public ResponseEntity<BalanceResponse> debit(@PathVariable UUID id,
                                                  @Valid @RequestBody BalanceChangeRequest request) {
        try {
            return ResponseEntity.ok(
                    balanceService.debit(id, request.getAmount(), request.getTransactionId()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok(balanceService.getBalance(id));
        }
    }

    @Operation(
            summary = "Hoàn tiền vào tài khoản (nội bộ)",
            description = "Chỉ dành cho lời gọi service-to-service, bắt buộc header X-Internal-Api-Key. "
                    + "Dùng để hoàn tiền khi giao dịch thất bại; idempotent theo transactionId.")
    @SecurityRequirement(name = "internalApiKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Hoàn tiền thành công, trả về số dư mới",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = BalanceResponse.class))),
            @ApiResponse(responseCode = "400",
                    description = "Dữ liệu không hợp lệ (amount phải lớn hơn 0, thiếu transactionId)",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403",
                    description = "Endpoint nội bộ, không được gọi trực tiếp (thiếu hoặc sai khóa nội bộ)",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404",
                    description = "Người dùng không tồn tại",
                    content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "409",
                    description = "Hoàn tiền không hợp lệ hoặc giao dịch đã được chốt trước đó",
                    content = @Content(mediaType = "application/json"))
    })
    @PostMapping("/users/{id}/credit")
    public ResponseEntity<BalanceResponse> credit(@PathVariable UUID id,
                                                   @Valid @RequestBody BalanceChangeRequest request) {
        try {
            return ResponseEntity.ok(
                    balanceService.credit(id, request.getAmount(), request.getTransactionId()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok(balanceService.getBalance(id));
        }
    }

    private void enforceOwnershipOrInternal(UUID targetUserId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication.getPrincipal();

        if ("internal-service".equals(principal)) {
            return;
        }
        if (principal instanceof UserDetailsImpl userDetails) {
            if (!userDetails.getId().equals(targetUserId)) {
                throw new AccessDeniedException("Không có quyền truy cập thông tin tài khoản này");
            }
            return;
        }
        throw new AccessDeniedException("Không xác định được danh tính người gọi");
    }
}