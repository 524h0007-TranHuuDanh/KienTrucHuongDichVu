package com.tdtu.ibanking.payment.repository;

import com.tdtu.ibanking.payment.entity.Tuition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.UUID;

public interface TuitionRepository extends JpaRepository<Tuition, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tuition t WHERE t.id = :id")
    Tuition findByIdForUpdate(@Param("id") UUID id);
}