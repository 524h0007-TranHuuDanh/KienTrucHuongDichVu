package com.tdtu.ibanking.tuition.service;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.tdtu.ibanking.tuition.AbstractPostgresIT;
import com.tdtu.ibanking.tuition.exception.TuitionAlreadyPaidException;

/**
 * RACE #2 (rubric 6.3): nhieu tai khoan cung thanh toan mot khoan hoc phi,
 * chi duoc phep 1 lan thanh cong.
 *
 * <p>10 luong goi {@code markPaid} tren cung mot tuitionId voi transactionId khac nhau.
 * Dung starting-gate (ready / start / done latch) de ep 10 luong that su dap vao DB
 * cung luc - neu chi submit tuan tu thi test se pass ngay ca khi da go bo
 * {@code @Lock(PESSIMISTIC_WRITE)}, tuc la xanh gia.
 *
 * <p>Chay tren Postgres that vi {@code SELECT ... FOR UPDATE} la thu quyet dinh ket qua.
 */
class TuitionMarkPaidConcurrencyIT extends AbstractPostgresIT {

    private static final int THREADS = 10;

    @Autowired
    private TuitionService tuitionService;

    private String ownMssv;
    private UUID tuitionId;

    @BeforeEach
    void setUp() {
        // Du lieu rieng cua test, khong dung row seed cua data.sql.
        ownMssv = uniqueMssv();
        insertStudent(ownMssv);
        tuitionId = insertUnpaidTuition(ownMssv, "HK1-2526", "2025-10-15", new BigDecimal("8500000.00"));
    }

    @AfterEach
    void tearDown() {
        deleteOwnData(ownMssv);
    }

    @Test
    @DisplayName("10 luong cung mark-paid mot khoan hoc phi -> dung 1 luong thanh cong, 9 luong bi tu choi")
    void onlyOneThreadCanMarkTheSameTuitionAsPaid() throws Exception {
        AtomicInteger winners = new AtomicInteger();
        AtomicInteger losers = new AtomicInteger();
        AtomicReference<UUID> winningTransactionId = new AtomicReference<>();
        ConcurrentLinkedQueue<Throwable> others = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> loserExceptionTypes = new ConcurrentLinkedQueue<>();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int i = 0; i < THREADS; i++) {
                final UUID transactionId = UUID.randomUUID(); // moi luong 1 giao dich khac nhau
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        tuitionService.markPaid(tuitionId, transactionId);
                        winners.incrementAndGet();
                        winningTransactionId.set(transactionId);
                    // CannotAcquireLockException la con cua PessimisticLockingFailureException
                    // nen khong the liet ke ca hai trong cung mot multi-catch; bat lop cha
                    // la du, ten lop cu the van duoc ghi lai o loserExceptionTypes.
                    } catch (TuitionAlreadyPaidException
                            | ObjectOptimisticLockingFailureException
                            | PessimisticLockingFailureException e) {
                        losers.incrementAndGet();
                        loserExceptionTypes.add(e.getClass().getName());
                    } catch (Throwable t) {
                        others.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertThat(ready.await(20, SECONDS)).as("10 luong da san sang").isTrue();
            start.countDown(); // mo cong: ca 10 luong lao vao cung luc
            assertThat(done.await(60, SECONDS)).as("10 luong da chay xong").isTrue();
        } finally {
            pool.shutdownNow();
        }

        // In ra de bao cao dung loai exception ma 9 luong thua thuc su nem ra.
        System.out.println("[RACE#2] loser exception types = " + List.copyOf(loserExceptionTypes));

        assertThat(others).as("khong duoc co exception ngoai du kien: " + others).isEmpty();
        assertThat(winners.get()).as("chi duoc dung 1 luong gach no thanh cong").isEqualTo(1);
        assertThat(losers.get()).as("9 luong con lai phai bi tu choi").isEqualTo(THREADS - 1);

        // Trang thai cuoi cung trong DB phai thuoc ve dung luong thang cuoc.
        Boolean paid = jdbcTemplate.queryForObject(
                "SELECT paid FROM tuitions WHERE id = ?", Boolean.class, tuitionId);
        UUID storedTransactionId = jdbcTemplate.queryForObject(
                "SELECT transaction_id FROM tuitions WHERE id = ?", UUID.class, tuitionId);

        assertThat(paid).isTrue();
        assertThat(storedTransactionId).isEqualTo(winningTransactionId.get());
    }
}
