package com.tdtu.ibanking.tuition.repository;

import com.tdtu.ibanking.tuition.entity.Tuition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TuitionRepository extends JpaRepository<Tuition, UUID> {

    @Query(value = "SELECT * FROM tuitions t WHERE t.mssv = :mssv AND t.paid = false " +
            "ORDER BY t.due_date ASC, t.semester ASC LIMIT 1", nativeQuery = true)
    Optional<Tuition> findFirstUnpaid(@Param("mssv") String mssv);

    List<Tuition> findByStudentMssvOrderByDueDateAsc(String mssv);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Tuition t WHERE t.id = :id")
    Optional<Tuition> findByIdForUpdate(@Param("id") UUID id);
}
