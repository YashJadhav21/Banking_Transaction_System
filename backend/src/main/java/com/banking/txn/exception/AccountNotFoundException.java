package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

public class AccountNotFoundException extends BankingException {

    public AccountNotFoundException(String message) {
        super(message, ErrorCategory.ACCOUNT_NOT_FOUND);
    }
}
