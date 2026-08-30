package com.tdtu.ibanking.auth.controller;

import com.tdtu.ibanking.auth.dto.BalanceChangeRequest;
import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.dto.LoginRequest;
import com.tdtu.ibanking.auth.dto.LoginResponse;
import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.repository.UserRepository;
import com.tdtu.ibanking.auth.security.JwtUtils;
import com.tdtu.ibanking.auth.service.BalanceService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceService balanceService;

    @GetMapping("/fix")
    public String fixPassword() {
        try {
            User user = userRepository.findByUsername("524h0088")
                    .orElse(null);
            if (user == null) {
                // Tạo mới user
                user = User.builder()
                        .username("524h0088")
                        .password(passwordEncoder.encode("123456"))
                        .fullName("Tran Huu Danh")
                        .email("danh@tdtu.edu.vn")
                        .balance(BigDecimal.valueOf(15000000))
                        .build();
                userRepository.save(user);
                return "Đã tạo user mới với password 123456!";
            } else {
                // Cập nhật password
                user.setPassword(passwordEncoder.encode("123456"));
                userRepository.save(user);
                return "Đã cập nhật mật khẩu!";
            }
        } catch (Exception e) {
            return "Lỗi: " + e.getMessage();
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(),
                            loginRequest.getPassword()
                    )
            );

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
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return ResponseEntity.ok(Map.of(
                "id", user.getId(),
                "email", user.getEmail(),
                "balance", user.getBalance()
        ));
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
}