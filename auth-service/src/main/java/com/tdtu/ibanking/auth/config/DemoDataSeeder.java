package com.tdtu.ibanking.auth.config;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Seed tài khoản demo lúc khởi động — thay cho endpoint GET /api/auth/fix đã xoá (A-01).
 * Chạy sau khi Hibernate đã tạo bảng, không có đường HTTP nào gọi tới.
 * Mỗi lần khởi động ghi đè lại password + balance để demo luôn về trạng thái đầu.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        // user "giàu" — chạy mọi happy path + là người trả A trong test thanh toán đồng thời
        upsert("524h0088", "Tran Huu Danh", "tanguyenthanhquy@gmail.com", "0901234088", "123456", new BigDecimal("100000000"));
        // học phí của chính user này (524H0456 = 20tr) > số dư -> demo ca 409 thiếu số dư;
        // đồng thời là người trả B trong test thanh toán đồng thời (trả học phí 524H0088)
        upsert("524h0456", "Pham Thi Mai", "524h0456@tdtu.edu.vn", "0901234456", "123456", new BigDecimal("15000000"));
        log.info("Demo users seeded (524h0088, 524h0456)");
    }

    private void upsert(String username, String fullName, String email, String phone,
                        String rawPassword, BigDecimal balance) {
        User user = userRepository.findByUsername(username).orElseGet(User::new);
        user.setUsername(username);
        user.setFullName(fullName);
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setBalance(balance);
        userRepository.save(user);
    }
}
