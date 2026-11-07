package com.tdtu.ibanking.payment.repository;

import com.tdtu.ibanking.payment.entity.Transaction;
import com.tdtu.ibanking.payment.entity.TransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Optional<Transaction> findFirstByTuitionIdAndStatusInOrderByCreatedAtDesc(
            UUID tuitionId, List<TransactionStatus> statuses);

    // API lịch sử giao dịch (đặc tả Mục 1: "Lịch sử các giao dịch đã thực hiện")
    List<Transaction> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<Transaction> findByStatusAndCreatedAtBefore(TransactionStatus status, LocalDateTime before);

    // Điều kiện status = PENDING nằm ngay trong câu UPDATE: nếu verify-otp vừa chuyển
    // giao dịch sang PROCESSING thì câu này không chạm vào, không ghi đè trạng thái saga.
    @Modifying
    @Transactional
    @Query("UPDATE Transaction t SET t.status = com.tdtu.ibanking.payment.entity.TransactionStatus.FAILED, "
            + "t.errorMessage = :message, t.updatedAt = :now "
            + "WHERE t.id = :id AND t.status = com.tdtu.ibanking.payment.entity.TransactionStatus.PENDING")
    int failIfPending(@Param("id") UUID id, @Param("message") String message, @Param("now") LocalDateTime now);
}
