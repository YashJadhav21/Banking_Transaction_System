package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

/** The request itself is invalid: bad amount, missing account, same-account transfer... */
public class InvalidTransactionException extends BankingException {

    public InvalidTransactionException(String message) {
        super(message, ErrorCategory.INVALID_TRANSACTION);
    }
}
