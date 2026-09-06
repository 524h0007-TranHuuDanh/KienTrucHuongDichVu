package com.tdtu.ibanking.tuition.controller;

import com.tdtu.ibanking.tuition.dto.MarkPaidRequest;
import com.tdtu.ibanking.tuition.dto.TuitionDetailResponse;
import com.tdtu.ibanking.tuition.dto.TuitionResponse;
import com.tdtu.ibanking.tuition.service.TuitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tuition")
@RequiredArgsConstructor
@Tag(name = "Tuition", description = "Tra cứu và gạch nợ học phí")
public class TuitionController {

    private final TuitionService tuitionService;

    @Operation(summary = "Lấy chi tiết học phí theo id", description = "Trả về đầy đủ thông tin một khoản học phí theo id. "
            + "Chấp nhận JWT người dùng HOẶC khóa nội bộ X-Internal-Api-Key (payment-service dùng khóa nội bộ).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy chi tiết học phí thành công", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TuitionDetailResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token không hợp lệ hoặc đã hết hạn", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Khóa nội bộ được gắn nhưng không hợp lệ: Endpoint nội bộ, không được gọi trực tiếp", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy khoản học phí", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "500", description = "Có lỗi xảy ra, vui lòng thử lại sau", content = @Content(mediaType = "application/json"))
    })
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "internalApiKey")
    @GetMapping("/id/{id}")
    public ResponseEntity<TuitionDetailResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(tuitionService.getById(id));
    }

    @Operation(summary = "Lấy toàn bộ học phí của một MSSV", description = "Trả về danh sách tất cả khoản học phí (đã đóng và chưa đóng) của sinh viên. Chỉ chấp nhận JWT người dùng.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lấy danh sách học phí thành công", content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = TuitionResponse.class)))),
            @ApiResponse(responseCode = "401", description = "Token không hợp lệ hoặc đã hết hạn", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy sinh viên hoặc khoản học phí", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "500", description = "Có lỗi xảy ra, vui lòng thử lại sau", content = @Content(mediaType = "application/json"))
    })
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/{mssv}/all")
    public ResponseEntity<List<TuitionResponse>> getAllByMssv(@PathVariable String mssv) {
        return ResponseEntity.ok(tuitionService.getAllByMssv(mssv));
    }

    @Operation(summary = "Tra cứu khoản học phí chưa đóng theo MSSV", description = "Trả về khoản học phí chưa thanh toán của sinh viên. "
            + "Chấp nhận JWT người dùng HOẶC khóa nội bộ X-Internal-Api-Key (payment-service dùng khóa nội bộ).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Tra cứu học phí thành công", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TuitionResponse.class))),
            @ApiResponse(responseCode = "401", description = "Token không hợp lệ hoặc đã hết hạn", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Khóa nội bộ được gắn nhưng không hợp lệ: Endpoint nội bộ, không được gọi trực tiếp", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy khoản học phí chưa đóng cho MSSV này", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "500", description = "Có lỗi xảy ra, vui lòng thử lại sau", content = @Content(mediaType = "application/json"))
    })
    @SecurityRequirement(name = "bearerAuth")
    @SecurityRequirement(name = "internalApiKey")
    @GetMapping("/{mssv}")
    public ResponseEntity<TuitionResponse> getUnpaidByMssv(@PathVariable String mssv) {
        return ResponseEntity.ok(tuitionService.getUnpaidByMssv(mssv));
    }

    @Operation(summary = "Gạch nợ học phí (nội bộ)", description = "Đánh dấu khoản học phí đã được thanh toán và gắn transactionId. "
            + "Chỉ payment-service được gọi bằng khóa nội bộ X-Internal-Api-Key; JWT người dùng không thay thế được.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Gạch nợ học phí thành công", content = @Content(mediaType = "application/json", schema = @Schema(implementation = TuitionDetailResponse.class))),
            @ApiResponse(responseCode = "400", description = "Dữ liệu không hợp lệ (ví dụ: transactionId là bắt buộc)", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "403", description = "Endpoint nội bộ, không được gọi trực tiếp", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "404", description = "Không tìm thấy khoản học phí", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "409", description = "Khoản học phí đã được thanh toán, hoặc: Khoản học phí này vừa được một giao dịch khác xử lý, vui lòng thử lại.", content = @Content(mediaType = "application/json")),
            @ApiResponse(responseCode = "500", description = "Có lỗi xảy ra, vui lòng thử lại sau", content = @Content(mediaType = "application/json"))
    })
    @SecurityRequirement(name = "internalApiKey")
    @PostMapping("/{id}/mark-paid")
    public ResponseEntity<TuitionDetailResponse> markPaid(@PathVariable UUID id,
                                                            @Valid @RequestBody MarkPaidRequest request) {
        return ResponseEntity.ok(tuitionService.markPaid(id, request.getTransactionId()));
    }
}
