package com.banking.txn.service;

import com.banking.txn.IntegrationTest;
import com.banking.txn.domain.ErrorCategory;
import com.banking.txn.domain.TransactionRequest;
import com.banking.txn.domain.TransactionResult;
import com.banking.txn.domain.TransactionStatus;
import com.banking.txn.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Each outcome of each operation, against a real PostgreSQL. */
class TransactionServiceTest extends IntegrationTest {

    @Autowired
    private TransactionService service;

    @BeforeEach
    void createAccounts() {
        createAccount("ACC0000001", "10000.00");
        createAccount("ACC0000002", "500.00");
        createAccount("ACC0000099", "1000.00", "INACTIVE");
    }

    // ---------- deposit ----------

    @Test
    void depositCreditsAccountAndRecordsSuccess() {
        TransactionResult result = service.process(TransactionRequest.deposit("TXN-D1", "ACC0000001", amount("250.50")));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("10250.50");
        assertThat(statusOf("TXN-D1")).isEqualTo("SUCCESS");
        assertThat(count("SELECT count(*) FROM transaction_audit WHERE transaction_id = 'TXN-D1' AND event = 'SUCCESS'"))
                .isEqualTo(1);
    }

    @Test
    void depositToInactiveAccountIsRejected() {
        TransactionResult result = service.process(TransactionRequest.deposit("TXN-D2", "ACC0000099", amount("10")));

        assertRejected(result, ErrorCategory.ACCOUNT_INACTIVE);
        assertThat(balanceOf("ACC0000099")).isEqualByComparingTo("1000.00");
        assertThat(failureCategoryOf("TXN-D2")).isEqualTo("ACCOUNT_INACTIVE");
    }

    // ---------- withdrawal ----------

    @Test
    void withdrawalDebitsAccount() {
        TransactionResult result = service.process(TransactionRequest.withdrawal("TXN-W1", "ACC0000001", amount("4000")));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("6000.00");
    }

    @Test
    void withdrawalOfEntireBalanceIsAllowed() {
        TransactionResult result = service.process(TransactionRequest.withdrawal("TXN-W2", "ACC0000002", amount("500.00")));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balanceOf("ACC0000002")).isEqualByComparingTo("0.00");
    }

    @Test
    void withdrawalWithInsufficientBalanceIsRejectedAndRecorded() {
        TransactionResult result = service.process(TransactionRequest.withdrawal("TXN-W3", "ACC0000002", amount("500.01")));

        assertRejected(result, ErrorCategory.INSUFFICIENT_BALANCE);
        assertThat(balanceOf("ACC0000002")).isEqualByComparingTo("500.00");
        assertThat(statusOf("TXN-W3")).isEqualTo("REJECTED");
        assertThat(failureCategoryOf("TXN-W3")).isEqualTo("INSUFFICIENT_BALANCE");
        assertThat(count("SELECT count(*) FROM transaction_audit WHERE transaction_id = 'TXN-W3' AND event = 'REJECTED'"))
                .isEqualTo(1);
    }

    @Test
    void withdrawalFromUnknownAccountIsRecordedWithoutAccountReference() {
        TransactionResult result = service.process(TransactionRequest.withdrawal("TXN-W4", "ACC0404404", amount("10")));

        assertRejected(result, ErrorCategory.ACCOUNT_NOT_FOUND);
        // The FK column cannot point at a missing account, so it is NULL...
        assertThat(jdbc.queryForObject("SELECT source_account_id FROM transaction WHERE transaction_id = 'TXN-W4'",
                String.class)).isNull();
        // ...but the account number the client sent is kept in the raw request.
        assertThat(jdbc.queryForObject("SELECT request_payload ->> 'sourceAccount' FROM failed_transaction "
                + "WHERE transaction_id = 'TXN-W4'", String.class)).isEqualTo("ACC0404404");
    }

    // ---------- transfer ----------

    @Test
    void transferMovesMoneyBetweenAccounts() {
        TransactionResult result = service.process(
                TransactionRequest.transfer("TXN-T1", "ACC0000001", "ACC0000002", amount("2500")));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("7500.00");
        assertThat(balanceOf("ACC0000002")).isEqualByComparingTo("3000.00");
    }

    @Test
    void transferInReverseIdOrderAlsoWorks() {
        // Source id is larger than destination id: exercises the other branch of the lock order.
        TransactionResult result = service.process(
                TransactionRequest.transfer("TXN-T2", "ACC0000002", "ACC0000001", amount("100")));

        assertThat(result.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(balanceOf("ACC0000002")).isEqualByComparingTo("400.00");
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("10100.00");
    }

    @Test
    void failedTransferChangesNeitherAccount() {
        TransactionResult result = service.process(
                TransactionRequest.transfer("TXN-T3", "ACC0000002", "ACC0000001", amount("600")));

        assertRejected(result, ErrorCategory.INSUFFICIENT_BALANCE);
        assertThat(balanceOf("ACC0000002")).isEqualByComparingTo("500.00");
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("10000.00");
    }

    @Test
    void transferToInactiveAccountIsRejectedAndSourceIsNotDebited() {
        TransactionResult result = service.process(
                TransactionRequest.transfer("TXN-T4", "ACC0000001", "ACC0000099", amount("100")));

        assertRejected(result, ErrorCategory.ACCOUNT_INACTIVE);
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("10000.00");
    }

    // ---------- validation and idempotency ----------

    @Test
    void invalidAmountIsRejectedAndStoredAsReceived() {
        TransactionResult result = service.process(TransactionRequest.deposit("TXN-V1", "ACC0000001", amount("-5.00")));

        assertRejected(result, ErrorCategory.INVALID_TRANSACTION);
        assertThat(jdbc.queryForObject("SELECT amount FROM transaction WHERE transaction_id = 'TXN-V1'", BigDecimal.class))
                .isEqualByComparingTo("-5.00");
        assertThat(failureCategoryOf("TXN-V1")).isEqualTo("INVALID_TRANSACTION");
    }

    @Test
    void requestWithoutIdIsRejectedButCannotBeStored() {
        TransactionResult result = service.process(
                new TransactionRequest(null, TransactionType.DEPOSIT, null, "ACC0000001", amount("10"), null));

        assertRejected(result, ErrorCategory.INVALID_TRANSACTION);
        assertThat(count("SELECT count(*) FROM transaction")).isZero();
    }

    @Test
    void repeatedTransactionIdMovesMoneyOnlyOnce() {
        TransactionRequest request = TransactionRequest.withdrawal("TXN-DUP", "ACC0000001", amount("1000"));

        TransactionResult first = service.process(request);
        TransactionResult second = service.process(request);

        assertThat(first.status()).isEqualTo(TransactionStatus.SUCCESS);
        assertRejected(second, ErrorCategory.DUPLICATE_TRANSACTION);
        assertThat(second.message()).contains("already processed with status SUCCESS");
        assertThat(balanceOf("ACC0000001")).isEqualByComparingTo("9000.00");
        assertThat(count("SELECT count(*) FROM transaction WHERE transaction_id = 'TXN-DUP'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transaction_audit WHERE transaction_id = 'TXN-DUP' "
                + "AND event = 'DUPLICATE_ATTEMPT'")).isEqualTo(1);
    }

    @Test
    void repeatingARejectedIdDoesNotProcessItAgain() {
        service.process(TransactionRequest.withdrawal("TXN-DUP2", "ACC0000002", amount("9999")));
        createAccount("ACC0000003", "50000.00");

        // Same id, now pointing at an account with enough money: still a duplicate.
        TransactionResult retry = service.process(TransactionRequest.withdrawal("TXN-DUP2", "ACC0000003", amount("9999")));

        assertRejected(retry, ErrorCategory.DUPLICATE_TRANSACTION);
        assertThat(balanceOf("ACC0000003")).isEqualByComparingTo("50000.00");
    }

    // ---------- helpers ----------

    private static void assertRejected(TransactionResult result, ErrorCategory category) {
        assertThat(result.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(result.errorCategory()).isEqualTo(category);
    }

    private String statusOf(String transactionId) {
        return jdbc.queryForObject("SELECT status FROM transaction WHERE transaction_id = ?", String.class, transactionId);
    }

    private String failureCategoryOf(String transactionId) {
        return jdbc.queryForObject("SELECT error_category FROM failed_transaction WHERE transaction_id = ?",
                String.class, transactionId);
    }

    private static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }
}
