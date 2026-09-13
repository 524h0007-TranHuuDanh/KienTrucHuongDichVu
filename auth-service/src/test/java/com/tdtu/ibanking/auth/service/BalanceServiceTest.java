package com.tdtu.ibanking.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.exception.InsufficientBalanceException;
import com.tdtu.ibanking.auth.exception.InvalidRefundException;
import com.tdtu.ibanking.auth.exception.TransactionAlreadyFinalizedException;
import com.tdtu.ibanking.auth.support.AbstractPostgresIT;

/**
 * Kiểm thử BalanceService trên Postgres thật (Testcontainers), dùng bean thật.
 * Mỗi test tự tạo user riêng nên không đụng tới dữ liệu demo của DemoDataSeeder.
 */
class BalanceServiceTest extends AbstractPostgresIT {

    @Autowired
    private BalanceService balanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** id các user do chính lớp test này tạo — chỉ dọn trong phạm vi này. */
    private final List<UUID> ownedUserIds = new ArrayList<>();

    @BeforeEach
    void cleanOwnScopeOnly() {
        // Không TRUNCATE cả bảng: dữ liệu seed và các test khác vẫn phải còn nguyên.
        for (UUID userId : ownedUserIds) {
            jdbcTemplate.update("DELETE FROM balance_entries WHERE user_id = ?", userId);
        }
        ownedUserIds.clear();
    }

    private User freshUser(String balance) {
        User user = createUser(new BigDecimal(balance));
        ownedUserIds.add(user.getId());
        return user;
    }

    private int countEntries(UUID userId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_entries WHERE user_id = ? AND type = ?",
                Integer.class, userId, type);
        return count == null ? 0 : count;
    }

    private BigDecimal balanceOf(UUID userId) {
        return userRepository.findById(userId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("debit trừ đúng số tiền và ghi đúng 1 dòng DEBIT")
    void debitSubtractsExactAmount() {
        User user = freshUser("1000000");

        BalanceResponse response = balanceService.debit(
                user.getId(), new BigDecimal("300000"), UUID.randomUUID());

        assertThat(response.getBalance()).isEqualByComparingTo(new BigDecimal("700000"));
        assertThat(balanceOf(user.getId())).isEqualByComparingTo(new BigDecimal("700000"));
        assertThat(countEntries(user.getId(), "DEBIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("debit lặp lại cùng transactionId chỉ trừ tiền một lần (idempotent)")
    void debitIsIdempotentPerTransactionId() {
        User user = freshUser("1000000");
        UUID transactionId = UUID.randomUUID();

        balanceService.debit(user.getId(), new BigDecimal("400000"), transactionId);
        BalanceResponse second = balanceService.debit(
                user.getId(), new BigDecimal("400000"), transactionId);

        // lần 2 chỉ trả về số dư hiện tại, không trừ thêm
        assertThat(second.getBalance()).isEqualByComparingTo(new BigDecimal("600000"));
        assertThat(balanceOf(user.getId())).isEqualByComparingTo(new BigDecimal("600000"));
        assertThat(countEntries(user.getId(), "DEBIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("debit khi số dư không đủ -> InsufficientBalanceException, số dư không đổi")
    void debitWithInsufficientBalanceThrows() {
        User user = freshUser("100000");
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() ->
                balanceService.debit(user.getId(), new BigDecimal("100001"), transactionId))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(user.getId())).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(countEntries(user.getId(), "DEBIT")).isZero();
    }

    @Test
    @DisplayName("credit khi chưa từng debit transactionId đó -> InvalidRefundException")
    void creditWithoutPriorDebitThrows() {
        User user = freshUser("1000000");
        UUID neverDebited = UUID.randomUUID();

        assertThatThrownBy(() ->
                balanceService.credit(user.getId(), new BigDecimal("100000"), neverDebited))
                .isInstanceOf(InvalidRefundException.class);

        assertThat(balanceOf(user.getId())).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(countEntries(user.getId(), "CREDIT")).isZero();
    }

    @Test
    @DisplayName("debit lại sau khi đã debit + credit cùng transactionId -> TransactionAlreadyFinalizedException")
    void debitAfterDebitAndCreditThrows() {
        User user = freshUser("1000000");
        UUID transactionId = UUID.randomUUID();

        balanceService.debit(user.getId(), new BigDecimal("250000"), transactionId);
        balanceService.credit(user.getId(), new BigDecimal("250000"), transactionId);

        assertThatThrownBy(() ->
                balanceService.debit(user.getId(), new BigDecimal("250000"), transactionId))
                .isInstanceOf(TransactionAlreadyFinalizedException.class);

        // đã hoàn tiền nên số dư về như cũ, và không có dòng DEBIT thứ hai
        assertThat(balanceOf(user.getId())).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(countEntries(user.getId(), "DEBIT")).isEqualTo(1);
        assertThat(countEntries(user.getId(), "CREDIT")).isEqualTo(1);
    }
}
