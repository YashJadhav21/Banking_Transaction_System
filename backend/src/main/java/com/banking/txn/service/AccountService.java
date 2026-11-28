package com.banking.txn.service;

import com.banking.txn.domain.Account;
import com.banking.txn.exception.AccountNotFoundException;
import com.banking.txn.repository.AccountRepository;
import org.springframework.stereotype.Service;

@Service
public class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    public Account getAccount(String accountId) {
        return accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException("Account does not exist"));
    }
}
