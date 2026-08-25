package com.tdtu.ibanking.payment.repository;

import com.tdtu.ibanking.payment.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
}