package com.tdtu.ibanking.account.repository;

import com.tdtu.ibanking.account.entity.BalanceEntry;
import com.tdtu.ibanking.account.entity.EntryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BalanceEntryRepository extends JpaRepository<BalanceEntry, UUID> {
    boolean existsByTransactionIdAndType(UUID transactionId, EntryType type);
}
