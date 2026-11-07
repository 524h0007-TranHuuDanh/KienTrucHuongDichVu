package com.tdtu.ibanking.account.exception;

import java.util.UUID;

public class TransactionAlreadyFinalizedException extends RuntimeException {
    public TransactionAlreadyFinalizedException(UUID transactionId) {
        super("Giao dịch " + transactionId + " đã bị huỷ (đã trừ và đã hoàn tiền), không thể trừ tiền lại");
    }
}
