package com.banking.txn.repository;

import com.banking.txn.domain.Channel;
import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionDetails;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionStatus;
import com.banking.txn.domain.TransactionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

/** Reads and writes the transaction and failed_transaction tables. */
@Repository
public class TransactionRepository {

    private static final RowMapper<TransactionDetails> DETAILS_MAPPER = (rs, rowNum) -> new TransactionDetails(
            rs.getString("transaction_id"),
            TransactionType.valueOf(rs.getString("type")),
            rs.getBigDecimal("amount"),
            rs.getString("source_account_id"),
            rs.getString("destination_account_id"),
            rs.getString("channel") == null ? null : Channel.valueOf(rs.getString("channel")),
            TransactionStatus.valueOf(rs.getString("status")),
            rs.getString("error_category") == null ? null : ErrorCategory.valueOf(rs.getString("error_category")),
            rs.getString("reason"),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("completed_at", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the transaction row unless one with the same transaction_id already
     * exists. Returns false when the id was already taken.
     *
     * This is the database half of idempotency: the UNIQUE constraint means only
     * one request can ever own a transaction_id, even if two arrive at the same time.
     * If another request has inserted the same id but not committed yet, PostgreSQL
     * makes this statement wait until that request finishes.
     */
    public boolean insertIfAbsent(TransactionRequest request, String sourceAccountId,
                                  String destinationAccountId, TransactionStatus status) {
        int rows = jdbc.update("""
                INSERT INTO transaction (transaction_id, type, amount, source_account_id,
                                         destination_account_id, channel, status, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp())
                ON CONFLICT (transaction_id) DO NOTHING
                """,
                request.transactionId(),
                request.type().name(),
                request.amount(),
                sourceAccountId,
                destinationAccountId,
                request.channel() == null ? null : request.channel().name(),
                status.name());
        return rows == 1;
    }

    /** Status of an already stored transaction, used for the fast duplicate check. */
    public Optional<String> findStatus(String transactionId) {
        return jdbc.queryForList("SELECT status FROM transaction WHERE transaction_id = ?",
                String.class, transactionId).stream().findFirst();
    }

    public void insertFailure(String transactionId, ErrorCategory category, String reason, String requestJson) {
        jdbc.update("""
                INSERT INTO failed_transaction (transaction_id, error_category, reason, request_payload)
                VALUES (?, ?, ?, ?::jsonb)
                """,
                transactionId, category.name(), reason, requestJson);
    }

    /** The transaction joined with its failure details (if it failed). */
    public Optional<TransactionDetails> findDetails(String transactionId) {
        return jdbc.query("""
                SELECT t.transaction_id, t.type, t.amount, t.source_account_id, t.destination_account_id,
                       t.channel, t.status, t.created_at, t.completed_at,
                       f.error_category, f.reason
                FROM transaction t
                LEFT JOIN failed_transaction f ON f.transaction_id = t.transaction_id
                WHERE t.transaction_id = ?
                """, DETAILS_MAPPER, transactionId).stream().findFirst();
    }
}
