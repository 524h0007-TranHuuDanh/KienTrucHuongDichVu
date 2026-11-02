package com.tdtu.ibanking.tuition.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TuitionDetailResponse {
    private UUID id;
    private String mssv;
    private String studentName;
    private String semester;
    private BigDecimal amount;
    private Boolean paid;
    private LocalDate dueDate;
    // Có offset múi giờ để client không hiểu nhầm thành giờ máy của họ.
    private OffsetDateTime paidAt;
    private UUID transactionId;
}
