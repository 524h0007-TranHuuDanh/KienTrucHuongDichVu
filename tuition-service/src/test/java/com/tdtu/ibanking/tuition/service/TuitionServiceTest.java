package com.tdtu.ibanking.tuition.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.tdtu.ibanking.tuition.AbstractPostgresIT;
import com.tdtu.ibanking.tuition.dto.TuitionDetailResponse;
import com.tdtu.ibanking.tuition.dto.TuitionResponse;
import com.tdtu.ibanking.tuition.exception.TuitionAlreadyPaidException;
import com.tdtu.ibanking.tuition.exception.TuitionNotFoundException;

/**
 * Test nghiep vu cua {@link TuitionService} tren Postgres that.
 *
 * <p>Test CHI DOC dung du lieu seed cua data.sql (524H0002 / 524H0003).
 * Test co GHI (markPaid) tu tao student + tuition rieng va tu xoa o {@link #cleanUp()},
 * khong bao gio gach no row seed.
 */
class TuitionServiceTest extends AbstractPostgresIT {

    @Autowired
    private TuitionService tuitionService;

    /** mssv rieng cua test hien tai, null neu test do khong tao du lieu. */
    private String ownMssv;

    @AfterEach
    void cleanUp() {
        deleteOwnData(ownMssv);
        ownMssv = null;
    }

    /** Tao 1 khoan hoc phi chua dong RIENG cua test va tra ve id. */
    private UUID givenOwnUnpaidTuition() {
        ownMssv = uniqueMssv();
        insertStudent(ownMssv);
        return insertUnpaidTuition(ownMssv, "HK1-2526", "2025-10-15", new BigDecimal("1234000.00"));
    }

    // ------------------------------------------------------------------
    // getUnpaidByMssv
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a. getUnpaidByMssv tra ve khoan co due_date SOM NHAT")
    void getUnpaidByMssv_returnsEarliestDueDate() {
        // 524H0002 (seed) no 2 ky: HK2-2425 due 2025-03-15 va HK1-2526 due 2025-10-15
        TuitionResponse response = tuitionService.getUnpaidByMssv("524H0002");

        assertThat(response.getSemester()).isEqualTo("HK2-2425");
        assertThat(response.getMssv()).isEqualTo("524H0002");
        assertThat(response.getPaid()).isFalse();
        assertThat(response.getAmount()).isEqualByComparingTo("5000000.00");
    }

    @Test
    @DisplayName("b. MSSV khong ton tai -> TuitionNotFoundException")
    void getUnpaidByMssv_studentNotFound_throws() {
        assertThatThrownBy(() -> tuitionService.getUnpaidByMssv("524H9999"))
                .isInstanceOf(TuitionNotFoundException.class)
                .hasMessageContaining("524H9999");
    }

    @Test
    @DisplayName("c. MSSV da dong het -> TuitionNotFoundException")
    void getUnpaidByMssv_allPaid_throws() {
        // 524H0003 (seed) chi co 1 khoan va da paid = true
        assertThatThrownBy(() -> tuitionService.getUnpaidByMssv("524H0003"))
                .isInstanceOf(TuitionNotFoundException.class)
                .hasMessageContaining("524H0003")
                .hasMessageContaining("không còn khoản học phí chưa đóng");
    }

    // ------------------------------------------------------------------
    // markPaid
    // ------------------------------------------------------------------

    @Test
    @DisplayName("d. markPaid set paid = true, paidAt != null va gan dung transactionId")
    void markPaid_marksTuitionAsPaid() {
        UUID tuitionId = givenOwnUnpaidTuition();
        UUID transactionId = UUID.randomUUID();

        TuitionDetailResponse response = tuitionService.markPaid(tuitionId, transactionId);

        assertThat(response.getId()).isEqualTo(tuitionId);
        assertThat(response.getPaid()).isTrue();
        assertThat(response.getPaidAt()).isNotNull();
        assertThat(response.getTransactionId()).isEqualTo(transactionId);

        // doc lai tu DB de chac chan da commit chu khong chi doi trong bo nho
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid FROM tuitions WHERE id = ?", Boolean.class, tuitionId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT transaction_id FROM tuitions WHERE id = ?", UUID.class, tuitionId))
                .isEqualTo(transactionId);
    }

    @Test
    @DisplayName("e. markPaid lap lai CUNG transactionId -> idempotent, khong nem loi")
    void markPaid_sameTransactionId_isIdempotent() {
        UUID tuitionId = givenOwnUnpaidTuition();
        UUID transactionId = UUID.randomUUID();

        TuitionDetailResponse first = tuitionService.markPaid(tuitionId, transactionId);
        LocalDateTime paidAtAfterFirst = jdbcTemplate.queryForObject(
                "SELECT paid_at FROM tuitions WHERE id = ?", LocalDateTime.class, tuitionId);

        // goi lai voi CUNG transactionId: khong duoc nem loi, tra ve nguyen trang
        TuitionDetailResponse second = tuitionService.markPaid(tuitionId, transactionId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getPaid()).isTrue();
        assertThat(second.getTransactionId()).isEqualTo(transactionId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT paid_at FROM tuitions WHERE id = ?", LocalDateTime.class, tuitionId))
                .isEqualTo(paidAtAfterFirst);
    }

    @Test
    @DisplayName("f. markPaid bang transactionId KHAC tren khoan da dong -> TuitionAlreadyPaidException")
    void markPaid_differentTransactionId_throwsAlreadyPaid() {
        UUID tuitionId = givenOwnUnpaidTuition();
        UUID firstTransactionId = UUID.randomUUID();
        UUID otherTransactionId = UUID.randomUUID();

        tuitionService.markPaid(tuitionId, firstTransactionId);

        assertThatThrownBy(() -> tuitionService.markPaid(tuitionId, otherTransactionId))
                .isInstanceOf(TuitionAlreadyPaidException.class);

        // transactionId cu KHONG bi ghi de
        assertThat(jdbcTemplate.queryForObject(
                "SELECT transaction_id FROM tuitions WHERE id = ?", UUID.class, tuitionId))
                .isEqualTo(firstTransactionId);
    }

    @Test
    @DisplayName("markPaid voi id khong ton tai -> TuitionNotFoundException")
    void markPaid_unknownId_throwsNotFound() {
        assertThatThrownBy(() -> tuitionService.markPaid(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(TuitionNotFoundException.class);
    }
}
