package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

public class InsufficientBalanceException extends BankingException {

    public InsufficientBalanceException(String message) {
        super(message, ErrorCategory.INSUFFICIENT_BALANCE);
    }
}
