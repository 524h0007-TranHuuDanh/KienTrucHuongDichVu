package com.tdtu.ibanking.account.controller;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tdtu.ibanking.account.dto.AccountBalanceResponse;
import com.tdtu.ibanking.account.dto.AccountResponse;
import com.tdtu.ibanking.account.dto.BalanceChangeRequest;
import com.tdtu.ibanking.account.dto.CreateAccountRequest;
import com.tdtu.ibanking.account.service.AccountBalanceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Toàn bộ endpoint dưới đây chỉ nhận lời gọi service-to-service kèm header
 * X-Internal-Api-Key (xem InternalApiKeyFilter + WebConfig) - account-service không
 * lộ ra api-gateway, không có JWT/login.
 */
@RestController
@RequestMapping("/api/account")
@Tag(name = "Account", description = "Quản lý account (số dư) nội bộ - chỉ gọi service-to-service")
@SecurityRequirement(name = "internalApiKey")
public class AccountController {

    @Autowired
    private AccountBalanceService accountBalanceService;

    @Operation(
            summary = "Tạo account mặc định cho user (idempotent)",
            description = "Nếu user đã có account mặc định thì trả về nguyên trạng (KHÔNG ghi đè số "
                    + "dư), chỉ tạo mới nếu chưa có. `initialBalance` optional, chỉ áp dụng lúc tạo mới "
                    + "- dùng cho seeding demo data. Luôn trả 200 kể cả khi tạo mới, vì hành vi idempotent "
                    + "khiến việc phân biệt 200/201 không có ý nghĩa với caller (proxy từ auth-service).")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Account mặc định (đã có từ trước hoặc vừa tạo mới)",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AccountResponse.class)))
    })
    @PostMapping("/users/{userId}/accounts")
    public ResponseEntity<AccountResponse> createDefaultAccount(
            @PathVariable UUID userId,
            @RequestBody(required = false) CreateAccountRequest request) {
        BigDecimal initialBalance = request == null ? null : request.getInitialBalance();
        return ResponseEntity.ok(accountBalanceService.createDefaultAccount(userId, initialBalance));
    }

    @Operation(summary = "Xem số dư account mặc định của user")
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Số dư hiện tại",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = AccountBalanceResponse.class))),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy account mặc định cho user")
    })
    @GetMapping("/users/{userId}/balance")
    public ResponseEntity<AccountBalanceResponse> getBalance(@PathVariable UUID userId) {
        return ResponseEntity.ok(accountBalanceService.getBalance(userId));
    }

    @Operation(
            summary = "Trừ tiền account mặc định của user",
            description = "Idempotent theo transactionId: gọi lại cùng transactionId chỉ trả về số dư "
                    + "hiện tại, không trừ thêm.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Trừ tiền thành công, trả về số dư mới"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy account mặc định cho user"),
            @ApiResponse(responseCode = "409", description = "Số dư không đủ hoặc giao dịch đã được chốt trước đó")
    })
    @PostMapping("/users/{userId}/debit")
    public ResponseEntity<AccountBalanceResponse> debit(@PathVariable UUID userId,
                                                          @Valid @RequestBody BalanceChangeRequest request) {
        try {
            return ResponseEntity.ok(
                    accountBalanceService.debit(userId, request.getAmount(), request.getTransactionId()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok(accountBalanceService.getBalance(userId));
        }
    }

    @Operation(
            summary = "Hoàn tiền vào account mặc định của user",
            description = "Idempotent theo transactionId; chỉ hợp lệ khi transactionId đó đã từng DEBIT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Hoàn tiền thành công, trả về số dư mới"),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ"),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy account mặc định cho user"),
            @ApiResponse(responseCode = "409", description = "Hoàn tiền không hợp lệ hoặc giao dịch đã được chốt trước đó")
    })
    @PostMapping("/users/{userId}/credit")
    public ResponseEntity<AccountBalanceResponse> credit(@PathVariable UUID userId,
                                                           @Valid @RequestBody BalanceChangeRequest request) {
        try {
            return ResponseEntity.ok(
                    accountBalanceService.credit(userId, request.getAmount(), request.getTransactionId()));
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity.ok(accountBalanceService.getBalance(userId));
        }
    }

}
