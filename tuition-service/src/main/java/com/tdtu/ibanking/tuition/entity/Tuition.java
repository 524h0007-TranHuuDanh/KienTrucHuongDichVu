package com.tdtu.ibanking.tuition.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tuitions", uniqueConstraints = @UniqueConstraint(columnNames = {"mssv", "semester"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tuition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mssv", referencedColumnName = "mssv", nullable = false)
    private Student student;

    @Column(name = "semester", length = 16, nullable = false)
    private String semester;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "paid", nullable = false)
    private Boolean paid = false;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "transaction_id", unique = true)
    private UUID transactionId;

    @Version
    @Column(name = "version")
    private Integer version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (paid == null) {
            paid = false;
        }
    }
}
