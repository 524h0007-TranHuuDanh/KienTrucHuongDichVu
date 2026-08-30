package com.tdtu.ibanking.tuition.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TuitionResponse {
    private UUID id;
    private String mssv;
    private String studentName;
    private String semester;
    private BigDecimal amount;
    private Boolean paid;
}
