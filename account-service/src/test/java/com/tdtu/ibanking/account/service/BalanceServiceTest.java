package com.tdtu.ibanking.account.service;

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

import com.tdtu.ibanking.account.dto.AccountBalanceResponse;
import com.tdtu.ibanking.account.entity.Account;
import com.tdtu.ibanking.account.exception.InsufficientBalanceException;
import com.tdtu.ibanking.account.exception.InvalidRefundException;
import com.tdtu.ibanking.account.exception.TransactionAlreadyFinalizedException;
import com.tdtu.ibanking.account.support.AbstractPostgresIT;

/**
 * Chuyển nguyên từ {@code auth-service/.../BalanceServiceTest.java}, đổi
 * User -> Account, userId -> accountId cho phù hợp domain account-service.
 * Ý nghĩa các test giữ nguyên 100%.
 */
class BalanceServiceTest extends AbstractPostgresIT {

    @Autowired
    private AccountBalanceService accountBalanceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** id account do chính lớp test này tạo - chỉ dọn trong phạm vi này. */
    private final List<UUID> ownedAccountIds = new ArrayList<>();

    @BeforeEach
    void cleanOwnScopeOnly() {
        // Không TRUNCATE cả bảng: dữ liệu seed và các test khác vẫn phải còn nguyên.
        for (UUID accountId : ownedAccountIds) {
            jdbcTemplate.update("DELETE FROM balance_entries WHERE account_id = ?", accountId);
        }
        ownedAccountIds.clear();
    }

    private Account freshAccount(String balance) {
        Account account = createAccount(new BigDecimal(balance));
        ownedAccountIds.add(account.getId());
        return account;
    }

    private int countEntries(UUID accountId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_entries WHERE account_id = ? AND type = ?",
                Integer.class, accountId, type);
        return count == null ? 0 : count;
    }

    private BigDecimal balanceOf(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getBalance();
    }

    @Test
    @DisplayName("debit trừ đúng số tiền và ghi đúng 1 dòng DEBIT")
    void debitSubtractsExactAmount() {
        Account account = freshAccount("1000000");

        AccountBalanceResponse response = accountBalanceService.debit(
                account.getUserId(), new BigDecimal("300000"), UUID.randomUUID());

        assertThat(response.getBalance()).isEqualByComparingTo(new BigDecimal("700000"));
        assertThat(balanceOf(account.getId())).isEqualByComparingTo(new BigDecimal("700000"));
        assertThat(countEntries(account.getId(), "DEBIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("debit lặp lại cùng transactionId chỉ trừ tiền một lần (idempotent)")
    void debitIsIdempotentPerTransactionId() {
        Account account = freshAccount("1000000");
        UUID transactionId = UUID.randomUUID();

        accountBalanceService.debit(account.getUserId(), new BigDecimal("400000"), transactionId);
        AccountBalanceResponse second = accountBalanceService.debit(
                account.getUserId(), new BigDecimal("400000"), transactionId);

        // lần 2 chỉ trả về số dư hiện tại, không trừ thêm
        assertThat(second.getBalance()).isEqualByComparingTo(new BigDecimal("600000"));
        assertThat(balanceOf(account.getId())).isEqualByComparingTo(new BigDecimal("600000"));
        assertThat(countEntries(account.getId(), "DEBIT")).isEqualTo(1);
    }

    @Test
    @DisplayName("debit khi số dư không đủ -> InsufficientBalanceException, số dư không đổi")
    void debitWithInsufficientBalanceThrows() {
        Account account = freshAccount("100000");
        UUID transactionId = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountBalanceService.debit(account.getUserId(), new BigDecimal("100001"), transactionId))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(balanceOf(account.getId())).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(countEntries(account.getId(), "DEBIT")).isZero();
    }

    @Test
    @DisplayName("credit khi chưa từng debit transactionId đó -> InvalidRefundException")
    void creditWithoutPriorDebitThrows() {
        Account account = freshAccount("1000000");
        UUID neverDebited = UUID.randomUUID();

        assertThatThrownBy(() ->
                accountBalanceService.credit(account.getUserId(), new BigDecimal("100000"), neverDebited))
                .isInstanceOf(InvalidRefundException.class);

        assertThat(balanceOf(account.getId())).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(countEntries(account.getId(), "CREDIT")).isZero();
    }

    @Test
    @DisplayName("debit lại sau khi đã debit + credit cùng transactionId -> TransactionAlreadyFinalizedException")
    void debitAfterDebitAndCreditThrows() {
        Account account = freshAccount("1000000");
        UUID transactionId = UUID.randomUUID();

        accountBalanceService.debit(account.getUserId(), new BigDecimal("250000"), transactionId);
        accountBalanceService.credit(account.getUserId(), new BigDecimal("250000"), transactionId);

        assertThatThrownBy(() ->
                accountBalanceService.debit(account.getUserId(), new BigDecimal("250000"), transactionId))
                .isInstanceOf(TransactionAlreadyFinalizedException.class);

        // đã hoàn tiền nên số dư về như cũ, và không có dòng DEBIT thứ hai
        assertThat(balanceOf(account.getId())).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(countEntries(account.getId(), "DEBIT")).isEqualTo(1);
        assertThat(countEntries(account.getId(), "CREDIT")).isEqualTo(1);
    }
}
