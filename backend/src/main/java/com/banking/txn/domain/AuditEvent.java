package com.banking.txn.domain;

/** Event types written to transaction_audit. */
public enum AuditEvent {
    SUCCESS,
    REJECTED,
    FAILED,
    DUPLICATE_ATTEMPT
}
