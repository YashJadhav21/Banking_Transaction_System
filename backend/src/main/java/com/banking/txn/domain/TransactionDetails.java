package com.banking.txn.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** A stored transaction plus its failure details (if any), for GET /api/transactions/{id}. */
public record TransactionDetails(
        String transactionId,
        TransactionType type,
        BigDecimal amount,
        String sourceAccount,
        String destinationAccount,
        Channel channel,
        TransactionStatus status,
        ErrorCategory errorCategory,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {
}
