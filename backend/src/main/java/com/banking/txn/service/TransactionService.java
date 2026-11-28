package com.banking.txn.service;

import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionDetails;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionResult;
import com.banking.txn.domain.TransactionStatus;
import com.banking.txn.exception.BankingException;
import com.banking.txn.exception.DuplicateTransactionException;
import com.banking.txn.logging.LogContext;
import com.banking.txn.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Entry point for processing one transaction. Follows the lifecycle from the brief:
 *
 * <pre>
 *   Receive -> Validate -> Check idempotency -> Begin + lock -> Apply -> Audit + commit -> Respond
 *              (validator)  (fast check here)   '------------ LedgerService -------------'
 * </pre>
 *
 * This class is deliberately NOT @Transactional. The money movement runs in its own
 * transaction inside {@link LedgerService}; if it fails, the failure is recorded by
 * {@link TransactionRecorder} in a second, separate transaction.
 *
 * It is thread-safe: it keeps no state between calls, so many worker threads can
 * call {@link #process} at the same time. All coordination happens in the database.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    /** Lock conflicts (deadlock or lock timeout) are retried this many times in total. */
    static final int MAX_ATTEMPTS = 3;

    private final TransactionValidator validator;
    private final LedgerService ledger;
    private final TransactionRecorder recorder;
    private final TransactionRepository transactions;

    public TransactionService(TransactionValidator validator, LedgerService ledger,
                              TransactionRecorder recorder, TransactionRepository transactions) {
        this.validator = validator;
        this.ledger = ledger;
        this.recorder = recorder;
        this.transactions = transactions;
    }

    public TransactionResult process(TransactionRequest request) {
        long startNanos = System.nanoTime();
        LogContext.put(request);
        try {
            TransactionResult result = processAndRecord(request, startNanos);
            logResult(result);
            return result;
        } finally {
            LogContext.clear();
        }
    }

    public Optional<TransactionDetails> findTransaction(String transactionId) {
        return transactions.findDetails(transactionId);
    }

    private TransactionResult processAndRecord(TransactionRequest request, long startNanos) {
        String id = request.transactionId();
        int attempt = 1;
        try {
            validator.validate(request);
            rejectIfAlreadyProcessed(request);

            // Retry only lock conflicts. Business rejections are final and are not retried.
            while (true) {
                try {
                    ledger.execute(request, attempt, startNanos);
                    break;
                } catch (PessimisticLockingFailureException e) {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw e;
                    }
                    log.warn("Lock conflict, retrying {}", kv("attempt", attempt));
                    attempt++;
                }
            }
            // Reaching this line means LedgerService returned, i.e. the COMMIT succeeded.
            return TransactionResult.success(id, Durations.millisSince(startNanos));

        } catch (DuplicateTransactionException e) {
            recordDuplicateSafely(request, startNanos);
            return TransactionResult.rejected(id, e.getCategory(), e.getMessage(), Durations.millisSince(startNanos));

        } catch (BankingException e) {
            // Business rule refused it: REJECTED.
            recordFailureSafely(request, TransactionStatus.REJECTED, e.getCategory(), e.getMessage(), attempt, startNanos);
            return TransactionResult.rejected(id, e.getCategory(), e.getMessage(), Durations.millisSince(startNanos));

        } catch (PessimisticLockingFailureException e) {
            // Still could not get the locks after all attempts: technical problem, FAILED.
            String reason = "Could not lock the accounts after " + MAX_ATTEMPTS + " attempts";
            log.error("Lock conflict, giving up", e);
            recordFailureSafely(request, TransactionStatus.FAILED, ErrorCategory.CONCURRENCY_CONFLICT, reason, attempt, startNanos);
            return TransactionResult.failed(id, ErrorCategory.CONCURRENCY_CONFLICT, reason, Durations.millisSince(startNanos));

        } catch (RuntimeException e) {
            // Anything unexpected (database down, bug...): FAILED. Details only in the log, not in the response.
            String reason = "Unexpected system error";
            log.error("Unexpected error while processing transaction", e);
            recordFailureSafely(request, TransactionStatus.FAILED, ErrorCategory.SYSTEM_ERROR, reason, attempt, startNanos);
            return TransactionResult.failed(id, ErrorCategory.SYSTEM_ERROR, reason, Durations.millisSince(startNanos));
        }
    }

    /**
     * Application half of idempotency: a cheap lookup that catches most repeats
     * before any lock is taken. The UNIQUE constraint (checked in LedgerService)
     * still catches two copies that arrive at exactly the same moment.
     */
    private void rejectIfAlreadyProcessed(TransactionRequest request) {
        Optional<String> existingStatus = transactions.findStatus(request.transactionId());
        if (existingStatus.isPresent()) {
            throw new DuplicateTransactionException(request.transactionId(), existingStatus.get());
        }
    }

    // Recording a failure must never hide the real outcome, so its own errors are only logged.

    private void recordFailureSafely(TransactionRequest request, TransactionStatus status, ErrorCategory category,
                                     String reason, int attempt, long startNanos) {
        try {
            recorder.recordFailure(request, status, category, reason, attempt, startNanos);
        } catch (RuntimeException e) {
            log.error("Could not record the failed transaction", e);
        }
    }

    private void recordDuplicateSafely(TransactionRequest request, long startNanos) {
        try {
            recorder.recordDuplicate(request, startNanos);
        } catch (RuntimeException e) {
            log.error("Could not record the duplicate attempt", e);
        }
    }

    /** One summary line per transaction. Level matches the outcome. */
    private void logResult(TransactionResult result) {
        BigDecimal durationMs = result.durationMs();
        switch (result.status()) {
            case SUCCESS -> log.info("Transaction processed {} {}",
                    kv("result", result.status()), kv("durationMs", durationMs));
            case REJECTED -> log.warn("Transaction rejected {} {} {} {}",
                    kv("result", result.status()), kv("errorCategory", result.errorCategory()),
                    kv("reason", result.message()), kv("durationMs", durationMs));
            case FAILED -> log.error("Transaction failed {} {} {} {}",
                    kv("result", result.status()), kv("errorCategory", result.errorCategory()),
                    kv("reason", result.message()), kv("durationMs", durationMs));
        }
    }
}
