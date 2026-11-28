package com.banking.txn.service;

import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionType;
import com.banking.txn.exception.InvalidTransactionException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plain unit test: no Spring, no database. */
class TransactionValidatorTest {

    private final TransactionValidator validator = new TransactionValidator();

    @Test
    void acceptsValidRequestsOfEveryType() {
        assertThatCode(() -> validator.validate(TransactionRequest.deposit("T1", "ACC0001", amount("100.00"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(TransactionRequest.withdrawal("T2", "ACC0001", amount("0.01"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(TransactionRequest.transfer("T3", "ACC0001", "ACC0002", amount("10000000.00"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingTransactionId() {
        assertInvalid(TransactionRequest.deposit(" ", "ACC0001", amount("10")), "transactionId is required");
    }

    @Test
    void rejectsMissingType() {
        assertInvalid(new TransactionRequest("T1", null, null, "ACC0001", amount("10"), null), "type is required");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertInvalid(TransactionRequest.deposit("T1", "ACC0001", amount("0")), "greater than zero");
        assertInvalid(TransactionRequest.deposit("T1", "ACC0001", amount("-5.00")), "greater than zero");
    }

    @Test
    void rejectsMoreThanTwoDecimalPlacesButAllowsTrailingZeros() {
        assertInvalid(TransactionRequest.deposit("T1", "ACC0001", amount("10.005")), "2 decimal places");
        assertThatCode(() -> validator.validate(TransactionRequest.deposit("T1", "ACC0001", amount("10.500"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAmountAboveLimit() {
        assertInvalid(TransactionRequest.deposit("T1", "ACC0001", amount("10000000.01")), "limit");
    }

    @Test
    void depositNeedsOnlyDestination() {
        assertInvalid(TransactionRequest.deposit("T1", null, amount("10")), "destinationAccount is required");
        assertInvalid(new TransactionRequest("T1", TransactionType.DEPOSIT, "ACC0002", "ACC0001", amount("10"), null),
                "sourceAccount must be empty");
    }

    @Test
    void withdrawalNeedsOnlySource() {
        assertInvalid(TransactionRequest.withdrawal("T1", null, amount("10")), "sourceAccount is required");
        assertInvalid(new TransactionRequest("T1", TransactionType.WITHDRAWAL, "ACC0001", "ACC0002", amount("10"), null),
                "destinationAccount must be empty");
    }

    @Test
    void transferNeedsTwoDifferentAccounts() {
        assertInvalid(TransactionRequest.transfer("T1", "ACC0001", null, amount("10")), "are required");
        assertInvalid(TransactionRequest.transfer("T1", "ACC0001", "ACC0001", amount("10")), "must be different");
    }

    @Test
    void fitsMoneyColumnMatchesNumeric19Scale2() {
        assertThat(TransactionValidator.fitsMoneyColumn(amount("99999999999999999.99"))).isTrue();
        assertThat(TransactionValidator.fitsMoneyColumn(amount("100000000000000000"))).isFalse();
        assertThat(TransactionValidator.fitsMoneyColumn(amount("1.001"))).isFalse();
    }

    private void assertInvalid(TransactionRequest request, String expectedMessagePart) {
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(InvalidTransactionException.class)
                .hasMessageContaining(expectedMessagePart);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
