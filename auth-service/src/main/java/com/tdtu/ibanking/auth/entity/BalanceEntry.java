package com.tdtu.ibanking.auth.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "balance_entries",
        uniqueConstraints = @UniqueConstraint(columnNames = {"transaction_id", "type"})
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BalanceEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 8)
    private EntryType type;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public BalanceEntry(UUID userId, UUID transactionId, EntryType type, BigDecimal amount) {
        this.userId = userId;
        this.transactionId = transactionId;
        this.type = type;
        this.amount = amount;
    }
}
