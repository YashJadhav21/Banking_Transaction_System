package com.banking.txn.service;

import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.exception.InvalidTransactionException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Checks the request on its own, before touching the database:
 * required fields, a valid amount, and the right accounts for the type.
 * (Whether the accounts exist / are active / have money is checked later,
 * under lock, in {@link LedgerService}.)
 */
@Component
public class TransactionValidator {

    /** Business limit for a single transaction: 1 crore (10,000,000.00). */
    public static final BigDecimal MAX_AMOUNT = new BigDecimal("10000000.00");

    public void validate(TransactionRequest request) {
        if (isBlank(request.transactionId())) {
            throw new InvalidTransactionException("transactionId is required");
        }
        if (request.transactionId().length() > 64) {
            throw new InvalidTransactionException("transactionId must be at most 64 characters");
        }
        if (request.type() == null) {
            throw new InvalidTransactionException("type is required");
        }
        validateAmount(request.amount());
        validateAccounts(request);
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null) {
            throw new InvalidTransactionException("amount is required");
        }
        if (amount.signum() <= 0) {
            throw new InvalidTransactionException("Amount must be greater than zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidTransactionException("Amount cannot have more than 2 decimal places");
        }
        if (amount.compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidTransactionException("Amount exceeds the per-transaction limit of 10000000.00");
        }
    }

    private void validateAccounts(TransactionRequest request) {
        String source = request.sourceAccount();
        String destination = request.destinationAccount();

        switch (request.type()) {
            case DEPOSIT -> {
                if (isBlank(destination)) {
                    throw new InvalidTransactionException("destinationAccount is required for a deposit");
                }
                if (source != null) {
                    throw new InvalidTransactionException("sourceAccount must be empty for a deposit");
                }
            }
            case WITHDRAWAL -> {
                if (isBlank(source)) {
                    throw new InvalidTransactionException("sourceAccount is required for a withdrawal");
                }
                if (destination != null) {
                    throw new InvalidTransactionException("destinationAccount must be empty for a withdrawal");
                }
            }
            case TRANSFER -> {
                if (isBlank(source) || isBlank(destination)) {
                    throw new InvalidTransactionException("sourceAccount and destinationAccount are required for a transfer");
                }
                if (source.equals(destination)) {
                    throw new InvalidTransactionException("Source and destination accounts must be different");
                }
            }
        }
    }

    /**
     * True if the amount fits the NUMERIC(19,2) column, i.e. it can be stored as-is.
     * Used before recording a rejected request.
     */
    public static boolean fitsMoneyColumn(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        int integerDigits = stripped.precision() - stripped.scale();
        return stripped.scale() <= 2 && integerDigits <= 17;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
