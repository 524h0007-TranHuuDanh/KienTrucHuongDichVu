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

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceService balanceService;

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