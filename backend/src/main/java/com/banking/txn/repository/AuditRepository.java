package com.banking.txn.repository;

import com.banking.txn.domain.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes the business audit trail (transaction_audit). */
@Repository
public class AuditRepository {

    private static final int MAX_DETAILS_LENGTH = 500;

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AuditEntry entry) {
        jdbc.update("""
                INSERT INTO transaction_audit (transaction_id, event, thread_name, attempt, duration_ms,
                                               source_balance_after, destination_balance_after, details)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.transactionId(),
                entry.event().name(),
                entry.threadName(),
                entry.attempt(),
                entry.durationMs(),
                entry.sourceBalanceAfter(),
                entry.destinationBalanceAfter(),
                truncate(entry.details()));
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_DETAILS_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_DETAILS_LENGTH);
    }
}
