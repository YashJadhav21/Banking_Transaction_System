package com.banking.txn.api;

import com.banking.txn.domain.Account;
import com.banking.txn.service.AccountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Returns the account and its current balance; 404 if it does not exist. */
    @GetMapping("/{accountId}")
    public Account get(@PathVariable String accountId) {
        return accountService.getAccount(accountId);
    }
}
