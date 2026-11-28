package com.banking.txn;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

/**
 * Base class for tests that need the full application and a real PostgreSQL.
 * All subclasses share one Spring context and one database container.
 * The database is emptied before every test so tests never depend on each other.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.execute("TRUNCATE transaction_audit, failed_transaction, transaction, account, customer "
                + "RESTART IDENTITY CASCADE");
    }

    protected void createAccount(String accountId, String balance) {
        createAccount(accountId, balance, "ACTIVE");
    }

    protected void createAccount(String accountId, String balance, String status) {
        Long customerId = jdbc.queryForObject(
                "INSERT INTO customer (full_name, email) VALUES ('Test Customer', ?) RETURNING customer_id",
                Long.class, accountId.toLowerCase() + "@example.com");
        jdbc.update("INSERT INTO account (account_id, customer_id, balance, status) VALUES (?, ?, ?, ?)",
                accountId, customerId, new BigDecimal(balance), status);
    }

    protected BigDecimal balanceOf(String accountId) {
        return jdbc.queryForObject("SELECT balance FROM account WHERE account_id = ?", BigDecimal.class, accountId);
    }

    protected int count(String sql, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        return count == null ? 0 : count;
    }
}
