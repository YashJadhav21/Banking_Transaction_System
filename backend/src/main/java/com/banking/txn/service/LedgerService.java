package com.banking.txn.service;

import com.banking.txn.domain.Account;
import com.banking.txn.domain.AuditEntry;
import com.banking.txn.domain.AuditEvent;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionStatus;
import com.banking.txn.exception.AccountInactiveException;
import com.banking.txn.exception.AccountNotFoundException;
import com.banking.txn.exception.DuplicateTransactionException;
import com.banking.txn.exception.InsufficientBalanceException;
import com.banking.txn.repository.AccountRepository;
import com.banking.txn.repository.AuditRepository;
import com.banking.txn.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Moves money. Everything in {@link #execute} runs in ONE database transaction:
 *
 * <pre>
 *   BEGIN
 *     1. lock the account row(s)        SELECT ... FOR UPDATE
 *     2. claim the transaction id       INSERT ... ON CONFLICT DO NOTHING
 *     3. check active + balance         (on the locked, up-to-date rows)
 *     4. update balance(s)
 *     5. write the audit row
 *   COMMIT                              (Spring commits when the method returns)
 * </pre>
 *
 * If any step throws, Spring rolls the whole transaction back: no balance change,
 * no transaction row, no audit row. The caller then records the failure separately.
 *
 * Why this is safe under concurrency:
 * - The row lock makes two withdrawals on the same account run one after the other,
 *   and the second one sees the balance the first one left. No lost updates, and
 *   no overdraft: of the ₹7,000 and ₹5,000 withdrawals from ₹10,000, the second sees
 *   only the money that is left and is rejected.
 * - Transfers lock both accounts in the same order (smaller id first), so two
 *   opposite transfers can never each hold one lock while waiting for the other
 *   (no deadlock).
 * - Isolation level is PostgreSQL's default, READ COMMITTED. That is enough here
 *   because FOR UPDATE always returns the latest committed version of the row.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final AuditRepository audits;

    public LedgerService(AccountRepository accounts, TransactionRepository transactions, AuditRepository audits) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.audits = audits;
    }

    @Transactional
    public void execute(TransactionRequest request, int attempt, long startNanos) {
        switch (request.type()) {
            case DEPOSIT -> deposit(request, attempt, startNanos);
            case WITHDRAWAL -> withdraw(request, attempt, startNanos);
            case TRANSFER -> transfer(request, attempt, startNanos);
        }
    }

    private void deposit(TransactionRequest request, int attempt, long startNanos) {
        Account account = lockAccount(request.destinationAccount(), "Destination");
        claimTransactionId(request);
        requireActive(account, "Destination");

        BigDecimal newBalance = accounts.credit(account.accountId(), request.amount());
        writeSuccessAudit(request, attempt, startNanos, null, newBalance);
    }

    private void withdraw(TransactionRequest request, int attempt, long startNanos) {
        Account account = lockAccount(request.sourceAccount(), "Source");
        claimTransactionId(request);
        requireActive(account, "Source");
        requireSufficientBalance(account, request.amount());

        BigDecimal newBalance = accounts.debit(account.accountId(), request.amount());
        writeSuccessAudit(request, attempt, startNanos, newBalance, null);
    }

    private void transfer(TransactionRequest request, int attempt, long startNanos) {
        String sourceId = request.sourceAccount();
        String destinationId = request.destinationAccount();

        // Deterministic lock order: always lock the smaller account id first.
        Account source;
        Account destination;
        if (sourceId.compareTo(destinationId) < 0) {
            source = lockAccount(sourceId, "Source");
            destination = lockAccount(destinationId, "Destination");
        } else {
            destination = lockAccount(destinationId, "Destination");
            source = lockAccount(sourceId, "Source");
        }

        claimTransactionId(request);
        requireActive(source, "Source");
        requireActive(destination, "Destination");
        requireSufficientBalance(source, request.amount());

        BigDecimal sourceBalance = accounts.debit(sourceId, request.amount());
        BigDecimal destinationBalance = accounts.credit(destinationId, request.amount());
        writeSuccessAudit(request, attempt, startNanos, sourceBalance, destinationBalance);
    }

    private Account lockAccount(String accountId, String role) {
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(role + " account does not exist"));
        log.debug("Locked {} account", role.toLowerCase());
        return account;
    }

    /**
     * Inserts the transaction row as SUCCESS. Nobody else can see it before COMMIT,
     * and if a later step fails it is rolled back together with the balance change.
     * Done right after locking so that a duplicate request (which waits for the same
     * lock) is reported as a duplicate, not as e.g. "insufficient balance".
     */
    private void claimTransactionId(TransactionRequest request) {
        boolean inserted = transactions.insertIfAbsent(
                request, request.sourceAccount(), request.destinationAccount(), TransactionStatus.SUCCESS);
        if (!inserted) {
            throw new DuplicateTransactionException(request.transactionId());
        }
    }

    private static void requireActive(Account account, String role) {
        if (!account.isActive()) {
            throw new AccountInactiveException(role + " account is " + account.status());
        }
    }

    private static void requireSufficientBalance(Account account, BigDecimal amount) {
        if (account.balance().compareTo(amount) < 0) {
            throw new InsufficientBalanceException("Insufficient balance for amount " + amount.toPlainString());
        }
    }

    private void writeSuccessAudit(TransactionRequest request, int attempt, long startNanos,
                                   BigDecimal sourceBalanceAfter, BigDecimal destinationBalanceAfter) {
        audits.insert(new AuditEntry(
                request.transactionId(),
                AuditEvent.SUCCESS,
                Thread.currentThread().getName(),
                attempt,
                Durations.millisSince(startNanos),
                sourceBalanceAfter,
                destinationBalanceAfter,
                null));
    }
}
