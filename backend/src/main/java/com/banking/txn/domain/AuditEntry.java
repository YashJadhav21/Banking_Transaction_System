package com.banking.txn.domain;

import java.math.BigDecimal;

/** One row of transaction_audit. Balances are null when not relevant. */
public record AuditEntry(
        String transactionId,
        AuditEvent event,
        String threadName,
        int attempt,
        BigDecimal durationMs,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        String details) {
}
