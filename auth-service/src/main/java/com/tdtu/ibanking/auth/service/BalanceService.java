package com.tdtu.ibanking.auth.service;

import com.tdtu.ibanking.auth.dto.BalanceResponse;
import com.tdtu.ibanking.auth.entity.BalanceEntry;
import com.tdtu.ibanking.auth.entity.EntryType;
import com.tdtu.ibanking.auth.entity.User;
import com.tdtu.ibanking.auth.exception.InsufficientBalanceException;
import com.tdtu.ibanking.auth.exception.InvalidRefundException;
import com.tdtu.ibanking.auth.exception.TransactionAlreadyFinalizedException;
import com.tdtu.ibanking.auth.exception.UserNotFoundException;
import com.tdtu.ibanking.auth.repository.BalanceEntryRepository;
import com.tdtu.ibanking.auth.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class BalanceService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BalanceEntryRepository balanceEntryRepository;

    @Transactional
    public BalanceResponse debit(UUID userId, BigDecimal amount, UUID transactionId) {
        boolean alreadyDebited = balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT);
        boolean alreadyRefunded = balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.CREDIT);

        if (alreadyDebited && alreadyRefunded) {
            throw new TransactionAlreadyFinalizedException(transactionId);
        }
        if (alreadyDebited) {
            return current(userId);
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getBalance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException();
        }

        user.setBalance(user.getBalance().subtract(amount));
        userRepository.save(user);
        balanceEntryRepository.save(new BalanceEntry(userId, transactionId, EntryType.DEBIT, amount));

        return toResponse(user);
    }

    @Transactional
    public BalanceResponse credit(UUID userId, BigDecimal amount, UUID transactionId) {
        if (balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.CREDIT)) {
            return current(userId);
        }
        if (!balanceEntryRepository.existsByTransactionIdAndType(transactionId, EntryType.DEBIT)) {
            throw new InvalidRefundException();
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        user.setBalance(user.getBalance().add(amount));
        userRepository.save(user);
        balanceEntryRepository.save(new BalanceEntry(userId, transactionId, EntryType.CREDIT, amount));

        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public BalanceResponse getBalance(UUID userId) {
        return current(userId);
    }

    private BalanceResponse current(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        return toResponse(user);
    }

    private BalanceResponse toResponse(User user) {
        return new BalanceResponse(user.getId(), user.getBalance());
    }
}