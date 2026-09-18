package com.tdtu.ibanking.auth.config;

import java.math.BigDecimal;

import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.tdtu.ibanking.auth.client.AccountServiceClient;
import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Tạo hai tài khoản demo lúc khởi động, sau khi Hibernate đã dựng xong bảng. Không có
 * endpoint HTTP nào chạm tới đây.
 *
 * <p>Mật khẩu được ghi đè mỗi lần khởi động, nhưng số dư thì không: nó nằm bên
 * account-service và ensureAccount là idempotent, nên giao dịch đã thực hiện không bị
 * reset về 100tr/15tr mỗi lần restart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DemoDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountServiceClient accountServiceClient;

    @Override
    @Transactional
    public void run(String... args) {
        // Dư dả, dùng cho mọi luồng chạy thuận
        upsert("524h0088", "Tran Huu Danh", "tanguyenthanhquy@gmail.com", "0901234088", "123456", new BigDecimal("100000000"));
        // 15tr, ít hơn học phí 20tr của 524H0004 — để demo ca thiếu số dư
        upsert("524h0456", "Pham Thi Mai", "thanhquytanguyen@gmail.com", "0901234456", "123456", new BigDecimal("15000000"));
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
        userRepository.save(user);

        try {
            accountServiceClient.ensureAccount(user.getId(), balance);
        } catch (RuntimeException e) {
            // account-service trục trặc thì cũng không nên kéo sập cả auth-service lúc
            // khởi động. User vẫn được tạo; nếu account thật sự thiếu, các lời gọi
            // balance/debit/credit sau đó trả 404 rõ ràng hơn nhiều một cú crash ở đây.
            log.warn("Không thể ensureAccount cho user {} trên account-service: {}",
                    username, e.getMessage());
        }
    }
}
