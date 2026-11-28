# Design Note

How the engine keeps balances correct under concurrency, never processes a transaction twice, and records every outcome. Each section names the class that implements it.

## 1. How one request flows

```
POST /api/transactions
   │
   ▼
TransactionController ── @Valid: missing fields / bad JSON → 400 (not stored)
   │
   ▼
TransactionService.process()                       ← NOT @Transactional
   1. Validate            TransactionValidator       → InvalidTransactionException
   2. Check idempotency   TransactionRepository.findStatus() → DuplicateTransactionException
   3-6. LedgerService.execute()                      ← ONE database transaction
          BEGIN
            lock account row(s)   SELECT … FOR UPDATE  (smaller id first)
            claim transaction id  INSERT … ON CONFLICT DO NOTHING
            check active + balance
            update balance(s)
            write audit row
          COMMIT
   7. Respond             TransactionResult (SUCCESS / REJECTED / FAILED)

   On any exception in 3-6: Spring rolls back everything, then
   TransactionRecorder writes the REJECTED/FAILED record in a NEW transaction.
```

**Key invariant:** `SUCCESS` is returned only after `LedgerService.execute()` returns. Returning normally means Spring has committed.

## 2. Transaction boundaries

| Where | Transaction | Why |
|---|---|---|
| `LedgerService.execute` | `@Transactional` | Debit, credit, transaction row and audit row must all happen or none happen. A transfer can never debit without crediting. |
| `TransactionRecorder.recordFailure` / `recordDuplicate` | `@Transactional`, separate | The ledger's rollback also removes its transaction row, so the failure has to be written afterwards, in its own transaction. |
| `TransactionService.process` | none | It only coordinates. Keeping it outside a transaction means the failure record is not lost by the rollback. |

`TransactionRecorder` is a separate class because Spring's `@Transactional` only works when the method is called from *another* bean. A call to a method in the same class skips the transaction proxy.

## 3. Locking: why pessimistic `SELECT … FOR UPDATE`

The brief allows pessimistic locking, optimistic locking, or an atomic SQL update. We chose **pessimistic**:

- **Easy to reason about.** Lock the row, check it, update it. Nobody else can change it in between.
- **No retry storms.** Optimistic locking would make many of the 20 workers fail and retry on popular accounts.
- **Exact rejection reasons.** We read the locked row, so we know whether the account is missing, inactive, or short of money.

How each problem is prevented:

| Problem | Prevented by |
|---|---|
| **Lost update** (two threads read 10,000 and both write their own result) | The second `FOR UPDATE` waits until the first commits, then reads the new balance. |
| **Dirty outcome** (acting on uncommitted data) | PostgreSQL never shows uncommitted data (READ COMMITTED). `FOR UPDATE` always returns the latest *committed* row. |
| **Negative balance** | The balance check runs on the locked row. As a final safety net, `CHECK (balance >= 0)` in the database refuses to commit a negative balance. |
| **Deadlock** on transfers | Both accounts are locked in the same order (smaller `account_id` first). Opposite transfers A→B and B→A therefore wait for the same first lock instead of each holding one and waiting for the other. |

**Mandatory scenario** (₹10,000; withdraw ₹7,000 and ₹5,000 at the same time): one thread gets the lock and withdraws, leaving ₹3,000 or ₹5,000. The other thread waits, then sees the reduced balance and is rejected with `INSUFFICIENT_BALANCE`. `ConcurrencyTest` runs this 50 times.

**Retries:** if PostgreSQL still reports a deadlock or lock timeout (`PessimisticLockingFailureException`), the whole ledger transaction is retried, up to 3 attempts in total. After that it is recorded as `FAILED / CONCURRENCY_CONFLICT`. Business rejections are never retried.

## 4. Idempotency (a repeated `transactionId` moves money once)

There are two layers, as the brief requires:

1. **Application check** (`TransactionService.rejectIfAlreadyProcessed`): a fast lookup before taking any lock. This catches ordinary retries cheaply, and the caller gets the original status (*"already processed with status SUCCESS"*).
2. **Database constraint** (`UNIQUE (transaction_id)`, used by `INSERT … ON CONFLICT DO NOTHING`): this catches two copies that arrive at *exactly* the same moment, which the first check cannot. Only one insert can win. If the other copy has inserted but not yet committed, PostgreSQL makes this insert wait, then reports the conflict.

The id is claimed **right after locking**, before the balance check. A duplicate waits for the same account lock, so by the time it runs, the original has committed. It is therefore reported as a duplicate, not as "insufficient balance".

A duplicate is answered with HTTP 409 and logged at WARN. It gets a `DUPLICATE_ATTEMPT` audit row on the original transaction. It gets no second transaction row and causes no balance change.

## 5. Outcomes, exceptions and status mapping

| Exception | ErrorCategory | Status | HTTP |
|---|---|---|---|
| — | — | SUCCESS | 200 |
| `InvalidTransactionException` | INVALID_TRANSACTION | REJECTED | 422 |
| `AccountNotFoundException` | ACCOUNT_NOT_FOUND | REJECTED | 422 |
| `AccountInactiveException` | ACCOUNT_INACTIVE | REJECTED | 422 |
| `InsufficientBalanceException` | INSUFFICIENT_BALANCE | REJECTED | 422 |
| `DuplicateTransactionException` | DUPLICATE_TRANSACTION | REJECTED | 409 |
| `PessimisticLockingFailureException` (after 3 attempts) | CONCURRENCY_CONFLICT | FAILED | 503 |
| any other `RuntimeException` | SYSTEM_ERROR | FAILED | 503 |

- **REJECTED** means a business rule said no. **FAILED** means something technical went wrong.
- All business exceptions extend `BankingException`, which carries the category. One `catch` block therefore handles them all consistently.
- Business exceptions skip stack-trace capture: they are expected outcomes, not bugs, and this keeps them cheap at 50,000 transactions.
- A malformed request (missing field, unparseable JSON) is answered with **400** by `GlobalExceptionHandler`. It is not stored, because without a valid id there is nothing to key the record on.

**What gets stored for a failure:** a `transaction` row with status REJECTED/FAILED, a `failed_transaction` row with the category, the reason and the raw request (JSON), and a `transaction_audit` row. An unknown account cannot go in the foreign-key column, so that column is NULL, but the account number is kept in `request_payload`.

## 6. Logging vs audit

| | Audit table | Logs |
|---|---|---|
| Purpose | Business record: what happened to the money | Explain execution: what the program did |
| Where | `transaction_audit`, `failed_transaction` | JSON lines on stdout |
| Written | Inside database transactions | Always, at the matching level |

- Every log line carries `transactionId`, `operation`, masked `sourceAccount`/`destinationAccount` (MDC, set in `LogContext`), plus `thread_name` and `@timestamp`.
- The summary line also adds `result`, `errorCategory`, `reason` and `durationMs`.
- **Levels:** INFO for success, WARN for rejections and retries, ERROR for failures, DEBUG for lock steps.
- Account numbers are masked to the last 4 characters (`*****0123`), and balances are never logged.

## 7. Indexes

Each index serves a known query. See [er-diagram.md](er-diagram.md#indexes-and-the-queries-they-serve). EXPLAIN ANALYZE evidence will be added in the performance phase.

## 8. Assumptions

- Currency is INR. Amounts must be > 0, have at most 2 decimals, and be at most 10,000,000.00 per transaction.
- A deposit has only a destination account, and a withdrawal only a source account. A transfer needs two different accounts.
- Processing is synchronous: the caller gets the final outcome in the response.
