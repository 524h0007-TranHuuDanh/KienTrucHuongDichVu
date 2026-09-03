package com.tdtu.ibanking.auth.repository;

import com.tdtu.ibanking.auth.entity.BalanceEntry;
import com.tdtu.ibanking.auth.entity.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, UUID> {
    boolean existsByTransactionIdAndType(UUID transactionId, EntryType type);
}
