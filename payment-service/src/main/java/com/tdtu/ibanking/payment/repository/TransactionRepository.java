package com.tdtu.ibanking.payment.repository;

import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findFirstByTuitionIdAndStatusInOrderByCreatedAtDesc(
            UUID tuitionId, List<TransactionStatus> statuses);
}