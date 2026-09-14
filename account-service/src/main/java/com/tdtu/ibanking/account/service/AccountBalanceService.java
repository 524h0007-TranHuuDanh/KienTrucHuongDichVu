package com.tdtu.ibanking.account.service;

import com.tdtu.ibanking.account.dto.AccountBalanceResponse;
import com.tdtu.ibanking.account.dto.AccountResponse;
import com.tdtu.ibanking.account.entity.Account;
import com.tdtu.ibanking.account.entity.AccountStatus;
import com.tdtu.ibanking.account.entity.BalanceEntry;
import com.tdtu.ibanking.account.entity.EntryType;
import com.tdtu.ibanking.account.exception.AccountNotFoundException;
import com.tdtu.ibanking.account.exception.InsufficientBalanceException;
import com.tdtu.ibanking.account.exception.InvalidRefundException;
import com.tdtu.ibanking.account.exception.TransactionAlreadyFinalizedException;
import com.tdtu.ibanking.account.repository.AccountRepository;
import com.tdtu.ibanking.account.repository.BalanceEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * Logic ledger/số dư, chuyển nguyên từ {@code auth-service.service.BalanceService},
 * đổi {@code User} -> {@code Account}. Debit/credit vẫn khoá {@code PESSIMISTIC_WRITE}
 * trên account mặc định của user và vẫn idempotent theo {@code transactionId} qua
 * ràng buộc UNIQUE(transaction_id, type) - không đơn giản hoá phần này.
 */
@Service
public class AccountBalanceService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private BalanceEntryRepository balanceEntryRepository;

    /**
     * Tạo account mặc định cho user - idempotent: nếu đã có account mặc định thì trả
     * về nguyên trạng (KHÔNG ghi đè số dư dù request có gửi initialBalance khác).
     */
    @Transactional
    public AccountResponse createDefaultAccount(UUID userId, BigDecimal initialBalance) {
        return accountRepository.findByUserIdAndIsDefaultTrue(userId)
                .map(this::toAccountResponse)
                .orElseGet(() -> {
                    Account account = Account.builder()
                            .userId(userId)
                            .accountNumber(generateUniqueAccountNumber())
                            .balance(initialBalance != null ? initialBalance : BigDecimal.ZERO)
                            .currency("VND")
                            .isDefault(true)
                            .status(AccountStatus.ACTIVE)
                            .build();
                    return toAccountResponse(accountRepository.save(account));
                });
    }

    @Transactional
    public AccountBalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        boolean alreadyDebited = balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT);
        boolean alreadyRefunded = balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.CREDIT);

        if (alreadyDebited && alreadyRefunded) {
            throw new TransactionAlreadyFinalizedException(transactionId);
        }
        if (alreadyDebited) {
            return current(userId);
        }

        Account account = accountRepository.findDefaultByUserIdForUpdate(userId)
                .orElseThrow(() -> AccountNotFoundException.forUser(userId));

        if (account.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException();
        }

        account.setBalance(account.getBalance().subtract(amount));
        accountRepository.save(account);
        balanceEntryRepository.save(new BalanceEntry(account.getId(), transactionId, EntryType.DEBIT, amount));

        return toBalanceResponse(account);
    }

    @Transactional
    public AccountBalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        if (balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.CREDIT)) {
            return current(userId);
        }
        if (!balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT)) {
            throw new InvalidRefundException();
        }

        Account account = accountRepository.findDefaultByUserIdForUpdate(userId)
                .orElseThrow(() -> AccountNotFoundException.forUser(userId));

        account.setBalance(account.getBalance().add(amount));
        accountRepository.save(account);
        balanceEntryRepository.save(new BalanceEntry(account.getId(), transactionId, EntryType.CREDIT, amount));

        return toBalanceResponse(account);
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getBalance(UUID userId) {
        return current(userId);
    }

    private AccountBalanceResponse current(UUID userId) {
        Account account = accountRepository.findByUserIdAndIsDefaultTrue(userId)
                .orElseThrow(() -> AccountNotFoundException.forUser(userId));
        return toBalanceResponse(account);
    }

    private String generateUniqueAccountNumber() {
        String candidate;
        do {
            // 12 chữ số ngẫu nhiên, không suy ra được từ username/CIF.
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 12; i++) {
                sb.append(RANDOM.nextInt(10));
            }
            candidate = sb.toString();
        } while (accountRepository.existsByAccountNumber(candidate));
        return candidate;
    }

    private AccountBalanceResponse toBalanceResponse(Account account) {
        return new AccountBalanceResponse(account.getId(), account.getUserId(), account.getBalance());
    }

    private AccountResponse toAccountResponse(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getUserId(),
                account.getAccountNumber(),
                account.getBalance(),
                account.getCurrency(),
                account.isDefault(),
                account.getStatus());
    }
}
