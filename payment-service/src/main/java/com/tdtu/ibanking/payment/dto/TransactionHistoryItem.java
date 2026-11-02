package com.tdtu.ibanking.payment.dto;

import com.tdtu.ibanking.payment.entity.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionHistoryItem {
    private UUID id;
    private UUID tuitionId;
    private String mssv;
    private String studentName;
    private String semester;
    private BigDecimal amount;
    private TransactionStatus status;
    private String errorMessage;
    // Có offset múi giờ: LocalDateTime trần bị trình duyệt hiểu là giờ máy người dùng,
    // container chạy UTC nên lệch 7 tiếng.
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
