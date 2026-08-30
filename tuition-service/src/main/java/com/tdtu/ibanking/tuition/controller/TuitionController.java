package com.tdtu.ibanking.tuition.controller;

import com.tdtu.ibanking.tuition.dto.MarkPaidRequest;
import com.tdtu.ibanking.tuition.dto.TuitionDetailResponse;
import com.tdtu.ibanking.tuition.dto.TuitionResponse;
import com.tdtu.ibanking.tuition.service.TuitionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tuition")
@RequiredArgsConstructor
public class TuitionController {

    private final TuitionService tuitionService;

    @GetMapping("/id/{id}")
    public ResponseEntity<TuitionDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(tuitionService.getById(id));
    }

    @GetMapping("/{mssv}/all")
    public ResponseEntity<List<TuitionResponse>> getAllByMssv(@PathVariable String mssv) {
        return ResponseEntity.ok(tuitionService.getAllByMssv(mssv));
    }

    @GetMapping("/{mssv}")
    public ResponseEntity<TuitionResponse> getUnpaidByMssv(@PathVariable String mssv) {
        return ResponseEntity.ok(tuitionService.getUnpaidByMssv(mssv));
    }

    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<TuitionDetailResponse> markPaid(@PathVariable UUID id,
                                                            @Valid @RequestBody MarkPaidRequest request) {
        return ResponseEntity.ok(tuitionService.markPaid(id, request.getTransactionId()));
    }
}
