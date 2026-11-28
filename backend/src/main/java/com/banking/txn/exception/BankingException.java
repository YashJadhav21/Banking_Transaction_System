package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

/**
 * Base class for business rule violations. Each subclass carries one
 * {@link ErrorCategory}, so the status and reason mapping is consistent everywhere.
 *
 * These are expected outcomes, not bugs, so the stack trace is not captured
 * (the last two constructor arguments). That keeps them cheap at 50,000 transactions.
 */
public abstract class BankingException extends RuntimeException {

    private final ErrorCategory category;

    protected BankingException(String message, ErrorCategory category) {
        super(message, null, false, false);
        this.category = category;
    }

    public ErrorCategory getCategory() {
        return category;
    }
}
