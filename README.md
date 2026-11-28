# Banking Transaction Processing System

A multi-threaded Java transaction engine for deposits, withdrawals and transfers that stays correct under concurrent load, never processes a transaction twice, and leaves a complete audit trail.

> **Status:** work in progress. The transaction engine (deposit, withdrawal, transfer with locking, idempotency and failure recording) and REST API are done; the 50,000-transaction simulator is next. See [Roadmap](#roadmap).

## Tech stack

| Layer | Choice |
|---|---|
| Language / build | Java 21, Maven (wrapper included) |
| Framework | Spring Boot 3.5 |
| Data access | Spring `JdbcTemplate` with hand-written SQL (no ORM), HikariCP |
| Database | PostgreSQL 16, schema managed by Flyway |
| Concurrency | Bounded `ExecutorService`; pessimistic row locks (`SELECT … FOR UPDATE`) in deterministic order |
| Logging | SLF4J + Logback, JSON output (logstash-logback-encoder), MDC context |
| Testing | JUnit 5, AssertJ, Mockito, Testcontainers (real PostgreSQL) |
| Extras | Spring Boot Actuator + Micrometer, OpenAPI / Swagger UI |
| Frontend (planned) | React + Vite + TypeScript |

## Quick start

**Prerequisite:** Docker Desktop (or Docker Engine with Compose v2).

```bash
docker compose up --build
```

This starts PostgreSQL, runs the schema migration and demo seed data, and starts the backend.

| What | URL |
|---|---|
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| PostgreSQL | `localhost:5432`, database / user / password: `banking` |

If port 8080 or 5432 is already in use, pick other ports:

```bash
BACKEND_PORT=8081 POSTGRES_PORT=5433 docker compose up --build
```

(or copy `.env.example` to `.env` and edit it).

To reset the database to the original seed state:

```bash
docker compose down -v && docker compose up --build
```

## Local development

Requires JDK 21 and Docker.

```bash
docker compose up -d postgres               # database only
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev   # human-readable logs
```

On Windows use `mvnw.cmd` instead of `./mvnw`.

### Running tests

```bash
cd backend
./mvnw verify
```

Tests start their own throw-away PostgreSQL 16 container through Testcontainers, so Docker must be running. An in-memory database is deliberately not used: locking and constraint behaviour must match production.

## Project structure

```
.
├── backend/                  Spring Boot application
│   ├── src/main/java/...     application code
│   ├── src/main/resources/   application.yml, logback-spring.xml
│   ├── src/test/java/...     unit + integration tests (Testcontainers)
│   └── Dockerfile
├── db/
│   ├── migration/            schema DDL (Flyway V1..V99)
│   └── seed/                 deterministic demo data (Flyway V100+, runtime only)
├── docs/                     ER diagram, design notes, performance results
├── docker-compose.yml
└── .env.example
```

## Database

Five tables: `customer`, `account`, `transaction`, `transaction_audit`, `failed_transaction`.
See the [ER diagram and constraint/index rationale](docs/er-diagram.md) and the DDL in [`db/migration/V1__create_schema.sql`](db/migration/V1__create_schema.sql).

Highlights:

- `CHECK (balance >= 0)` on accounts: the database itself refuses negative balances.
- `UNIQUE (transaction_id)`: the idempotency key; duplicate requests can never move money twice.
- Status model: `SUCCESS`, `REJECTED` (business rule refused it), `FAILED` (technical problem), plus a transient `PENDING`.
- Indexes are justified by the reporting and history queries they serve.

Demo seed: 200 customers, 1,000 accounts (`ACC000001`–`ACC001000`), every 50th account inactive.

## API

All endpoints are also browsable in Swagger UI (`/swagger-ui.html`).

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/transactions` | Process a deposit, withdrawal or transfer |
| `GET` | `/api/transactions/{transactionId}` | Stored transaction with status and failure reason |
| `GET` | `/api/accounts/{accountId}` | Account and current balance |

Example (same shape as the assignment's sample transaction):

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"transactionId":"TXN100234","sourceAccount":"ACC000001","destinationAccount":"ACC000004","amount":12500.00,"type":"TRANSFER"}'
```

```json
{"transactionId":"TXN100234","status":"SUCCESS","errorCategory":null,"message":"Transaction completed successfully","durationMs":80.720}
```

| HTTP | Meaning |
|---|---|
| 200 | `SUCCESS` - money moved and committed |
| 409 | `REJECTED` / `DUPLICATE_TRANSACTION` - id already processed, no money moved |
| 422 | `REJECTED` - business rule (invalid amount, unknown/inactive account, insufficient balance) |
| 503 | `FAILED` - technical problem (lock conflict after retries, database error) |
| 400 | Malformed request (missing field, invalid JSON) - not stored |

How it works - transaction boundaries, locking, idempotency, status mapping - is explained in the [design note](docs/design-note.md).

## Roadmap

- [x] Project scaffold, Docker Compose, Maven wrapper
- [x] Database schema, constraints, indexes, seed data, ER diagram
- [x] Schema constraint tests (Testcontainers)
- [x] Deposit / withdrawal / transfer service with validation and custom exceptions
- [x] Pessimistic locking with deterministic lock order, idempotency, failure recording
- [ ] Workload simulator (50,000+ transactions) with 1 / 5 / 10 / 20 workers
- [x] Concurrency and duplicate tests (mandatory ₹10,000 scenario x50, opposite transfers, concurrent duplicates)
- [ ] Rollback-on-failure test (fault injection mid-transfer)
- [x] Structured JSON logging with MDC and account masking
- [ ] Reporting SQL and EXPLAIN ANALYZE analysis
- [ ] Performance results and analysis
- [ ] React dashboard
- [x] Design note (transaction boundaries, locking, idempotency)
- [ ] Architecture diagram
