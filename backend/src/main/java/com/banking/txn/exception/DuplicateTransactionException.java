package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

/** The transaction id was already processed; money must not move a second time. */
public class DuplicateTransactionException extends BankingException {

    public DuplicateTransactionException(String transactionId) {
        super("Transaction " + transactionId + " was already processed", ErrorCategory.DUPLICATE_TRANSACTION);
    }

    public DuplicateTransactionException(String transactionId, String originalStatus) {
        super("Transaction " + transactionId + " was already processed with status " + originalStatus,
                ErrorCategory.DUPLICATE_TRANSACTION);
    }
}
