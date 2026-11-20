package com.banking.txn.schema;

import com.banking.txn.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that the database itself enforces the core invariants, independent of
 * application code. Each test runs in a transaction that is rolled back afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Transactional
class SchemaConstraintsTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long customerId;

    @BeforeEach
    void createCustomerWithTwoAccounts() {
        customerId = jdbc.queryForObject(
                "INSERT INTO customer (full_name, email) VALUES ('Test User', 'test.user@example.com') RETURNING customer_id",
                Long.class);
        jdbc.update("INSERT INTO account (account_id, customer_id, balance) VALUES ('ACC9000001', ?, 10000.00)", customerId);
        jdbc.update("INSERT INTO account (account_id, customer_id, balance) VALUES ('ACC9000002', ?, 500.00)", customerId);
    }

    @Test
    @DisplayName("Flyway creates all five tables")
    void allTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class);

        assertThat(tables).contains("customer", "account", "transaction", "transaction_audit", "failed_transaction");
    }

    @Test
    @DisplayName("Database refuses a negative balance")
    void negativeBalanceIsRejected() {
        assertThatThrownBy(() -> jdbc.update("UPDATE account SET balance = balance - 10000.01 WHERE account_id = 'ACC9000001'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_account_balance_non_neg");
    }

    @Test
    @DisplayName("Duplicate transaction_id is rejected by the unique constraint")
    void duplicateTransactionIdIsRejected() {
        String insert = "INSERT INTO transaction (transaction_id, type, amount, destination_account_id) "
                + "VALUES ('TXN-DUP-1', 'DEPOSIT', 100.00, 'ACC9000001')";
        jdbc.update(insert);

        assertThatThrownBy(() -> jdbc.update(insert))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_transaction_transaction_id");
    }

    @Test
    @DisplayName("Email uniqueness ignores case")
    void emailIsUniqueCaseInsensitive() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO customer (full_name, email) VALUES ('Other', 'TEST.USER@example.com')"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_customer_email_ci");
    }

    @Test
    @DisplayName("A SUCCESS transfer cannot use the same account on both sides")
    void successfulSelfTransferIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, source_account_id, destination_account_id, status, completed_at) "
                        + "VALUES ('TXN-SELF-1', 'TRANSFER', 100.00, 'ACC9000001', 'ACC9000001', 'SUCCESS', now())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transaction_success_shape");
    }

    @Test
    @DisplayName("A SUCCESS deposit must not have a source account")
    void successfulDepositWithSourceIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, source_account_id, destination_account_id, status, completed_at) "
                        + "VALUES ('TXN-DEP-BAD', 'DEPOSIT', 100.00, 'ACC9000002', 'ACC9000001', 'SUCCESS', now())"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transaction_success_shape");
    }

    // Note: after a constraint violation PostgreSQL aborts the surrounding
    // transaction, so each test performs at most one failing statement, last.

    @Test
    @DisplayName("Non-positive amount is refused on a non-REJECTED row")
    void nonPositiveAmountRefusedUnlessRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, destination_account_id) "
                        + "VALUES ('TXN-NEG-PENDING', 'DEPOSIT', -5.00, 'ACC9000001')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transaction_amount");
    }

    @Test
    @DisplayName("A REJECTED row keeps the invalid amount as received (honest audit)")
    void nonPositiveAmountAllowedWhenRejected() {
        assertThatCode(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, destination_account_id, status, completed_at) "
                        + "VALUES ('TXN-NEG-REJECTED', 'DEPOSIT', -5.00, 'ACC9000001', 'REJECTED', now())"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A finished transaction must have completed_at; a pending one must not")
    void completedAtMatchesStatus() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, destination_account_id, status) "
                        + "VALUES ('TXN-NO-COMPLETE', 'DEPOSIT', 10.00, 'ACC9000001', 'SUCCESS')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_transaction_completed_at");
    }

    @Test
    @DisplayName("Transaction cannot reference a non-existent account")
    void unknownAccountIsRejectedByForeignKey() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO transaction (transaction_id, type, amount, destination_account_id) "
                        + "VALUES ('TXN-FK-1', 'DEPOSIT', 10.00, 'ACC0000404')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_transaction_destination");
    }

    @Test
    @DisplayName("Account ids must follow the ACC + digits format")
    void accountIdFormatIsEnforced() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO account (account_id, customer_id) VALUES ('BAD-1', ?)", customerId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_account_id_format");
    }

    @Test
    @DisplayName("Failure categories are restricted to the known set")
    void failureCategoryIsRestricted() {
        jdbc.update("INSERT INTO transaction (transaction_id, type, amount, source_account_id, status, completed_at) "
                + "VALUES ('TXN-FAIL-1', 'WITHDRAWAL', 99999.00, 'ACC9000002', 'REJECTED', now())");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO failed_transaction (transaction_id, error_category, reason) VALUES ('TXN-FAIL-1', 'OOPS', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_failed_transaction_cat");
    }
}
