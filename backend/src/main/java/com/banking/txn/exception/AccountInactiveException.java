package com.banking.txn.exception;

import com.banking.txn.domain.ErrorCategory;

public class AccountInactiveException extends BankingException {

    public AccountInactiveException(String message) {
        super(message, ErrorCategory.ACCOUNT_INACTIVE);
    }
}
