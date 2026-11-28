package com.banking.txn.domain;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A transaction request, in the same shape as the sample in the assignment:
 * <pre>
 * { "transactionId": "TXN100234", "sourceAccount": "ACC1001",
 *   "destinationAccount": "ACC2004", "amount": 12500.00, "type": "TRANSFER" }
 * </pre>
 * The annotations only reject requests that are malformed (HTTP 400): missing
 * fields or an amount that cannot be stored. Business rules (amount > 0, accounts
 * exist, enough balance, ...) are checked by the service, and those rejections are
 * recorded in the database.
 */
public record TransactionRequest(
        @NotBlank @Size(max = 64) String transactionId,
        @NotNull TransactionType type,
        String sourceAccount,
        String destinationAccount,
        @NotNull @Digits(integer = 17, fraction = 2) BigDecimal amount,
        Channel channel) {

    public static TransactionRequest deposit(String transactionId, String account, BigDecimal amount) {
        return new TransactionRequest(transactionId, TransactionType.DEPOSIT, null, account, amount, null);
    }

    public static TransactionRequest withdrawal(String transactionId, String account, BigDecimal amount) {
        return new TransactionRequest(transactionId, TransactionType.WITHDRAWAL, account, null, amount, null);
    }

    public static TransactionRequest transfer(String transactionId, String from, String to, BigDecimal amount) {
        return new TransactionRequest(transactionId, TransactionType.TRANSFER, from, to, amount, null);
    }
}
