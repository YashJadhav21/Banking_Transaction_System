package com.banking.txn.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;

public record Account(
        String accountId,
        long customerId,
        String accountType,
        BigDecimal balance,
        AccountStatus status) {

    @JsonIgnore // helper only; status is already in the JSON
    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
