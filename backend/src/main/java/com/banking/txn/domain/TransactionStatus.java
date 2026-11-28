package com.banking.txn.domain;

/**
 * Final outcome of a transaction.
 * <ul>
 *   <li>SUCCESS  - money moved and the database commit succeeded.</li>
 *   <li>REJECTED - a business rule refused it (bad amount, unknown/inactive account,
 *                  insufficient balance, duplicate id).</li>
 *   <li>FAILED   - a technical problem (lock conflict after retries, database error).</li>
 * </ul>
 */
public enum TransactionStatus {
    SUCCESS,
    REJECTED,
    FAILED
}
