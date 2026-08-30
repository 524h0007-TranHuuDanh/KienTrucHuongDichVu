package com.tdtu.ibanking.tuition.repository;

import com.tdtu.ibanking.tuition.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StudentRepository extends JpaRepository<Student, UUID> {

    Optional<Student> findByMssv(String mssv);

    boolean existsByMssv(String mssv);
}
