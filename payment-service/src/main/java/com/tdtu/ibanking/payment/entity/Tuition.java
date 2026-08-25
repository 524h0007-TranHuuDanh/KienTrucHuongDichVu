package com.tdtu.ibanking.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "tuitions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tuition {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String mssv;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false)
    private Boolean paid = false;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Version
    private Integer version;
}