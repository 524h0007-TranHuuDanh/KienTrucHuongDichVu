package com.tdtu.ibanking.account.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tdtu.ibanking.account.entity.Account;
import com.tdtu.ibanking.account.exception.InsufficientBalanceException;
import com.tdtu.ibanking.account.support.AbstractPostgresIT;

/**
 * RACE #1 (rubric 6.2): nhiều giao dịch đồng thời trên cùng một account
 * không được phép chi vượt số dư. Chuyển nguyên từ
 * {@code auth-service/.../BalanceConcurrencyIT.java}, đổi User -> Account,
 * userId -> accountId/userId của account-service. Ý nghĩa test race-condition
 * (khoá pessimistic khi nhiều request debit cùng lúc) giữ nguyên 100%.
 *
 * <p>10 thread cùng trừ 2.000.000 từ account có đúng 10.000.000 -> chỉ 5 lệnh
 * được phép thành công, 5 lệnh còn lại phải bị từ chối vì thiếu số dư, và số dư
 * cuối cùng phải bằng 0 (không âm).
 *
 * <p>Dùng starting gate (ready/start/done latch) để các thread thật sự chạy chồng
 * lấn nhau; nếu chỉ submit tuần tự thì test vẫn xanh kể cả khi khoá bị hỏng.
 *
 * <p>không đánh {@code @Transactional} lên lớp test: mỗi thread con chạy transaction
 * riêng và commit thật, rollback của test cha sẽ che mất dữ liệu đó.
 */
class BalanceConcurrencyIT extends AbstractPostgresIT {

    private static final int THREADS = 10;
    private static final BigDecimal STARTING_BALANCE = new BigDecimal("10000000");
    private static final BigDecimal DEBIT_AMOUNT = new BigDecimal("2000000");

    @Autowired
    private AccountBalanceService accountBalanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> ownedAccountIds = new ArrayList<>();

    @BeforeEach
    void cleanOwnScopeOnly() {
        // Chỉ dọn dữ liệu của account do chính lớp test này tạo, không TRUNCATE bảng.
        for (UUID accountId : ownedAccountIds) {
            jdbcTemplate.update("DELETE FROM balance_entries WHERE account_id = ?", accountId);
        }
        ownedAccountIds.clear();
    }

    @Test
    @DisplayName("10 lệnh trừ tiền đồng thời trên cùng account: đúng 5 lệnh thành công, số dư không âm")
    void concurrentDebitsOnSameAccountNeverOverspend() throws Exception {
        Account account = createAccount(STARTING_BALANCE);
        ownedAccountIds.add(account.getId());
        UUID userId = account.getUserId();

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> others = new ConcurrentLinkedQueue<>();

        // pool >= số thread, nếu nhỏ hơn thì các lệnh bị ép chạy tuần tự
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int i = 0; i < THREADS; i++) {
                UUID transactionId = UUID.randomUUID(); // mỗi thread một giao dịch khác nhau
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        accountBalanceService.debit(userId, DEBIT_AMOUNT, transactionId);
                        successes.incrementAndGet();
                    } catch (InsufficientBalanceException e) {
                        insufficient.incrementAndGet();
                    } catch (Throwable t) {
                        others.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            // chứng minh cả 10 thread đã thực sự tới cổng xuất phát
            assertThat(ready.await(20, SECONDS)).isTrue();
            start.countDown();
            // bắt deadlock thay vì treo vô hạn
            assertThat(done.await(60, SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(others).isEmpty();
        assertThat(successes.get()).isEqualTo(5);
        assertThat(insufficient.get()).isEqualTo(5);

        BigDecimal finalBalance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        assertThat(finalBalance.compareTo(BigDecimal.ZERO))
                .as("số dư cuối phải đúng bằng 0 và không được âm")
                .isEqualTo(0);

        Integer debitRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_entries WHERE account_id = ? AND type = 'DEBIT'",
                Integer.class, account.getId());
        assertThat(debitRows).isEqualTo(5);
    }
}
