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
 * Seed tài khoản demo lúc khởi động — thay cho endpoint GET /api/auth/fix đã xoá (A-01).
 * Chạy sau khi Hibernate đã tạo bảng, không có đường HTTP nào gọi tới.
 * Mỗi lần khởi động ghi đè lại password; số dư (balance) từ Phase 4 trở đi KHÔNG còn
 * lưu ở auth-service nữa — được tạo/đảm bảo tồn tại bên account-service qua
 * AccountServiceClient.ensureAccount (idempotent: nếu user đã có account mặc định thì
 * account-service giữ nguyên số dư hiện có, KHÔNG ghi đè lại 100tr/15tr mỗi lần khởi động).
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
        // user "giàu" — chạy mọi happy path + là người trả A trong test thanh toán đồng thời
        upsert("524h0088", "Tran Huu Danh", "tanguyenthanhquy@gmail.com", "0901234088", "123456", new BigDecimal("100000000"));
        // số dư 15tr < học phí của MSSV 524H0004 (20tr) -> demo ca 409 thiếu số dư;
        // đồng thời là người trả B trong test thanh toán đồng thời (cùng trả học phí 524H0001)
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
            // Không để lỗi tạm thời của account-service (chưa sẵn sàng, mạng chập chờn...)
            // làm sập context lúc khởi động auth-service. User vẫn được tạo bình thường;
            // nếu account-service thực sự không có account cho user này, các lời gọi
            // balance/debit/credit sau đó sẽ trả 404 rõ ràng thay vì crash lúc seed.
            log.warn("Không thể ensureAccount cho user {} trên account-service: {}",
                    username, e.getMessage());
        }
    }
}
