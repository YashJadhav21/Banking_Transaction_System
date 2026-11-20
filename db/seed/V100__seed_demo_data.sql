-- =============================================================================
-- Demo seed data - deterministic, so every environment starts identical.
--   * 200 customers
--   * 1,000 accounts (5 per customer), ids ACC000001 .. ACC001000
--   * balances between 10,000.00 and 99,999.00 (deterministic formula)
--   * every 50th account INACTIVE (20 accounts) to exercise rejection paths
--
-- Loaded by Flyway only in the default (runtime) profile. Automated tests load
-- db/migration only and create exactly the rows each test needs.
-- Version 100 leaves room for schema migrations V2..V99.
-- =============================================================================

INSERT INTO customer (full_name, email, phone)
SELECT (ARRAY['Aarav','Vivaan','Aditya','Ananya','Diya','Ishaan','Kavya','Rohan','Saanvi','Arjun',
              'Meera','Kabir','Priya','Reyansh','Aisha','Vihaan','Neha','Siddharth','Tara','Yash'])[1 + (g.n % 20)]
       || ' ' ||
       (ARRAY['Sharma','Patel','Iyer','Reddy','Nair','Gupta','Joshi','Kulkarni','Mehta','Rao',
              'Das','Singh','Jadhav','Menon','Bose'])[1 + ((g.n / 20) % 15)],
       'customer' || lpad(g.n::text, 4, '0') || '@example.com',
       '+91-9' || lpad(((g.n * 104729) % 1000000000)::text, 9, '0')
FROM generate_series(1, 200) AS g(n)
ORDER BY g.n;

-- Map account n to the ((n-1)/5 + 1)-th customer by position, so the seed does
-- not depend on identity values starting at 1.
INSERT INTO account (account_id, customer_id, account_type, balance, status)
SELECT 'ACC' || lpad(a.n::text, 6, '0'),
       c.customer_id,
       CASE WHEN a.n % 3 = 0 THEN 'CURRENT' ELSE 'SAVINGS' END,
       (10000 + (a.n * 7919) % 90000)::NUMERIC(19,2),
       CASE WHEN a.n % 50 = 0 THEN 'INACTIVE' ELSE 'ACTIVE' END
FROM generate_series(1, 1000) AS a(n)
JOIN (SELECT customer_id, row_number() OVER (ORDER BY customer_id) AS rn
      FROM customer) AS c
  ON c.rn = ((a.n - 1) / 5) + 1;
