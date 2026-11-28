package com.banking.txn.domain;

import java.math.BigDecimal;

/** What the engine returns for every request. errorCategory is null on success. */
public record TransactionResult(
        String transactionId,
        TransactionStatus status,
        ErrorCategory errorCategory,
        String message,
        BigDecimal durationMs) {

    public static TransactionResult success(String transactionId, BigDecimal durationMs) {
        return new TransactionResult(transactionId, TransactionStatus.SUCCESS, null,
                "Transaction completed successfully", durationMs);
    }

    public static TransactionResult rejected(String transactionId, ErrorCategory category, String message,
                                             BigDecimal durationMs) {
        return new TransactionResult(transactionId, TransactionStatus.REJECTED, category, message, durationMs);
    }

    public static TransactionResult failed(String transactionId, ErrorCategory category, String message,
                                           BigDecimal durationMs) {
        return new TransactionResult(transactionId, TransactionStatus.FAILED, category, message, durationMs);
    }
}
