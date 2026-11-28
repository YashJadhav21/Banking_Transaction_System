package com.banking.txn.domain;

/** Where the transaction came from. Optional on a request. */
public enum Channel {
    ATM,
    INTERNET,
    MOBILE,
    BRANCH
}
