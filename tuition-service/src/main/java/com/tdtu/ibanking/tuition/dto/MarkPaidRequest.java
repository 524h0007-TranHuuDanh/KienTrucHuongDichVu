package com.tdtu.ibanking.tuition.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MarkPaidRequest {

    @NotNull(message = "transactionId là bắt buộc")
    private UUID transactionId;
}
