package com.banking.txn.service;

import com.banking.txn.domain.AuditEntry;
import com.banking.txn.domain.AuditEvent;
import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionStatus;
import com.banking.txn.repository.AccountRepository;
import com.banking.txn.repository.AuditRepository;
import com.banking.txn.repository.TransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records transactions that did NOT succeed.
 *
 * Why a separate class and a separate database transaction?
 * When {@link LedgerService} fails, its transaction is rolled back, and the rollback
 * also removes the transaction row it had inserted. So the REJECTED/FAILED record
 * must be written afterwards, in a new transaction of its own. It is a separate Spring
 * bean because @Transactional only applies when the method is called from another bean.
 */
@Service
public class TransactionRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransactionRecorder.class);

    private final TransactionRepository transactions;
    private final AccountRepository accounts;
    private final AuditRepository audits;
    private final ObjectMapper objectMapper;

    public TransactionRecorder(TransactionRepository transactions, AccountRepository accounts,
                               AuditRepository audits, ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.audits = audits;
        this.objectMapper = objectMapper;
    }

    /** Stores a REJECTED or FAILED transaction: transaction row + failure reason + audit row. */
    @Transactional
    public void recordFailure(TransactionRequest request, TransactionStatus status, ErrorCategory category,
                              String reason, int attempt, long startNanos) {
        if (!canBeStored(request)) {
            // E.g. no transaction id: there is nothing to key a record on. The log line is the trace.
            log.warn("Request cannot be stored in the database; outcome is recorded in the logs only");
            return;
        }

        // The account columns are foreign keys, so an unknown account is stored as NULL.
        // The account number the client sent is still kept in request_payload.
        String sourceId = existingAccountOrNull(request.sourceAccount());
        String destinationId = existingAccountOrNull(request.destinationAccount());

        boolean inserted = transactions.insertIfAbsent(request, sourceId, destinationId, status);
        if (!inserted) {
            // Another request with the same id was stored first (e.g. a retry that arrived at the same time).
            audits.insert(auditEntry(request, AuditEvent.DUPLICATE_ATTEMPT, attempt, startNanos,
                    "Id already recorded; this attempt was " + status + ": " + reason));
            return;
        }

        transactions.insertFailure(request.transactionId(), category, reason, toJson(request));
        AuditEvent event = (status == TransactionStatus.FAILED) ? AuditEvent.FAILED : AuditEvent.REJECTED;
        audits.insert(auditEntry(request, event, attempt, startNanos, reason));
    }

    /** A repeated transaction id: no new transaction row, just an audit entry on the original. */
    @Transactional
    public void recordDuplicate(TransactionRequest request, long startNanos) {
        audits.insert(auditEntry(request, AuditEvent.DUPLICATE_ATTEMPT, 1, startNanos,
                "Duplicate request ignored; balances not changed"));
    }

    /** True if every NOT NULL column can be filled with what the client sent. */
    private static boolean canBeStored(TransactionRequest request) {
        return request.transactionId() != null
                && !request.transactionId().isBlank()
                && request.transactionId().length() <= 64
                && request.type() != null
                && request.amount() != null
                && TransactionValidator.fitsMoneyColumn(request.amount());
    }

    private String existingAccountOrNull(String accountId) {
        if (accountId == null || !accounts.exists(accountId)) {
            return null;
        }
        return accountId;
    }

    private static AuditEntry auditEntry(TransactionRequest request, AuditEvent event, int attempt,
                                         long startNanos, String details) {
        return new AuditEntry(request.transactionId(), event, Thread.currentThread().getName(), attempt,
                Durations.millisSince(startNanos), null, null, details);
    }

    private String toJson(TransactionRequest request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
