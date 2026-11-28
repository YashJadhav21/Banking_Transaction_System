package com.banking.txn.repository;

import com.banking.txn.domain.Account;
import com.banking.txn.domain.AccountStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class AccountRepository {

    private static final String SELECT_ACCOUNT =
            "SELECT account_id, customer_id, account_type, balance, status FROM account WHERE account_id = ?";

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, rowNum) -> new Account(
            rs.getString("account_id"),
            rs.getLong("customer_id"),
            rs.getString("account_type"),
            rs.getBigDecimal("balance"),
            AccountStatus.valueOf(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Plain read, no lock. Used for display only - never to decide a balance change. */
    public Optional<Account> findById(String accountId) {
        return jdbc.query(SELECT_ACCOUNT, ACCOUNT_MAPPER, accountId).stream().findFirst();
    }

    /**
     * Reads the account and locks its row until the current database transaction
     * commits or rolls back. Any other transaction that wants to lock the same row
     * waits, so balance checks and updates on one account happen one at a time.
     * Must be called inside a @Transactional method.
     */
    public Optional<Account> findByIdForUpdate(String accountId) {
        return jdbc.query(SELECT_ACCOUNT + " FOR UPDATE", ACCOUNT_MAPPER, accountId).stream().findFirst();
    }

    public boolean exists(String accountId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM account WHERE account_id = ?)", Boolean.class, accountId);
        return Boolean.TRUE.equals(exists);
    }

    /** Adds money and returns the new balance. Caller must hold the row lock. */
    public BigDecimal credit(String accountId, BigDecimal amount) {
        return jdbc.queryForObject(
                "UPDATE account SET balance = balance + ?, updated_at = now() WHERE account_id = ? RETURNING balance",
                BigDecimal.class, amount, accountId);
    }

    /**
     * Removes money and returns the new balance. Caller must hold the row lock and
     * have checked the balance. The CHECK (balance >= 0) constraint is a final safety net.
     */
    public BigDecimal debit(String accountId, BigDecimal amount) {
        return jdbc.queryForObject(
                "UPDATE account SET balance = balance - ?, updated_at = now() WHERE account_id = ? RETURNING balance",
                BigDecimal.class, amount, accountId);
    }
}
