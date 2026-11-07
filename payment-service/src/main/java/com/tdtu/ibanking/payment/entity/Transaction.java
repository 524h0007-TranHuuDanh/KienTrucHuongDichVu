package com.tdtu.ibanking.payment.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "tuition_id", nullable = false)
    private UUID tuitionId;

    // Snapshot MSSV + ho ten sinh vien tai thoi diem khoi tao giao dich, de lich su
    // hien "da dong cho ai" ma khong phai goi nguoc tuition-service cho tung dong (N+1).
    // Nullable: cac giao dich tao truoc thay doi nay khong co du lieu nay.
    @Column(name = "mssv", length = 20)
    private String mssv;

    @Column(name = "student_name", length = 128)
    private String studentName;

    // Snapshot hoc ky cua khoan hoc phi: mot MSSV co the no nhieu ky, khong co
    // truong nay thi lich su chi thay so tien va khong biet dang xem ky nao.
    @Column(name = "semester", length = 32)
    private String semester;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}