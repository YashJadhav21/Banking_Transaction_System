package com.banking.txn.domain;

/**
 * Why a transaction did not succeed. Every exception maps to exactly one category,
 * which is stored in failed_transaction.error_category and written to the logs.
 */
public enum ErrorCategory {
    INVALID_TRANSACTION,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_INACTIVE,
    INSUFFICIENT_BALANCE,
    /** Returned to the caller and audited, but not stored in failed_transaction
     *  (the original transaction already owns that id). */
    DUPLICATE_TRANSACTION,
    CONCURRENCY_CONFLICT,
    SYSTEM_ERROR
}
