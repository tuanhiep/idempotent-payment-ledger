# Idempotent Payment Ledger

Track: `system-design`

Production-style module for retry-safe payment intake and balanced ledger mutation. It accepts a payment request with an `Idempotency-Key`, persists the first outcome, replays duplicate requests with the same payload, rejects reused keys with different payloads, and records balanced debit/credit ledger entries.

The bar for this module is evidence, not labels: every claim should be implemented in code, covered by tests, or listed as a production gap.

## Problem

Payment APIs live in an unreliable world:

- clients retry after timeouts;
- servers may process a request but fail before the client receives the response;
- duplicate requests can double-charge a payer;
- reused idempotency keys with changed payloads can hide caller bugs or abuse;
- ledger entries must remain balanced even under retries.

The system must make payment intake retry-safe while preserving auditability and correctness.

## Design Invariants

- `Idempotency-Key` identifies one logical payment attempt.
- The first accepted payload for a key owns that key.
- A duplicate request with the same key and same payload returns the stored response.
- A duplicate request with the same key and a different payload returns `409 Conflict`.
- A successful payment creates exactly two ledger entries: one debit and one credit.
- Ledger entries for one transaction must sum to zero.
- Invalid requests must not mutate ledger state.
- The durable adapter uses PostgreSQL uniqueness and Flyway-managed schema as the production-like correctness boundary.

## Running the Application

### 1. Start Infrastructure
Spin up the PostgreSQL and Redis containers:
```bash
docker compose -f system-design/idempotent-payment-ledger/compose.yml up -d
```

### 2. Run Application
Choose one of the two active profiles to run the application:

*   **Default Mode (PostgreSQL)**:
    Uses PostgreSQL for both durable idempotency records and ledger state. This is the smallest runnable correctness configuration and does not require Redis.
    ```bash
    ./mvnw -pl system-design/idempotent-payment-ledger spring-boot:run
    ```

*   **Production-Like Hybrid Mode (PostgreSQL + Redis)**:
    Uses PostgreSQL as the authoritative correctness boundary and Redis as an outer reservation/cache layer. Redis reservations carry owner tokens and use atomic compare-and-set/delete scripts so an expired owner cannot overwrite or delete a replacement lease. This mode demonstrates production-style failure handling; throughput and capacity claims remain deferred until measured.
    ```bash
    ./mvnw -pl system-design/idempotent-payment-ledger spring-boot:run -Dspring-boot.run.profiles=jpa,redis
    ```

### 3. Seed Local Demo Accounts

After the application starts and Flyway creates the schema, load the two accounts used by
the request example:

```bash
docker compose -f system-design/idempotent-payment-ledger/compose.yml exec -T postgres \
  psql -U paymentledger -d paymentledger \
  < system-design/idempotent-payment-ledger/scripts/seed-local-accounts.sql
```

Demo data is deliberately kept outside Flyway migrations so schema rollout never inserts
test accounts.

## Implemented Endpoints

Create a payment:

```bash
curl -i -X POST "http://localhost:8080/api/payments" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-payment-001" \
  -d '{
    "payerAccountId": "acct-payer",
    "merchantAccountId": "acct-merchant",
    "amount": 100.00,
    "currency": "USD"
  }'
```

Replay the same request with the same key to receive the original outcome with `replayed=true`.

Change the amount while reusing the key to receive `409 Conflict`.

## Test Matrix

Covered by `PaymentIntakeServiceTest`:

- first payment creates two balanced ledger entries;
- duplicate request with the same key and payload returns the stored response;
- concurrent duplicate requests with the same key create one ledger transaction in the demo process;
- duplicate key with a different payload is rejected;
- invalid amount is rejected before ledger mutation.

Covered by `PaymentControllerIntegrationTest`:

- HTTP duplicate request is replayed;
- HTTP duplicate key with changed payload returns `409 Conflict`.

Covered by persistence and upgrade-path tests:

- PostgreSQL uniqueness resolves concurrent writers to one payment plus one replay;
- account locking prevents concurrent overdraft;
- Redis stale owners cannot delete or complete over replacement reservations;
- Flyway V4 preserves historical accounts referenced by ledger entries while removing unused fixtures.

`PaymentLedgerApplicationTests` verifies Spring context wiring.

Persistence tests use PostgreSQL through Testcontainers and the same Flyway
migration used by local Docker. Docker must be running for the persistence
suite; these tests fail fast rather than falling back to H2.

## Production Gaps

- The in-memory adapter remains only for fast unit-level semantics tests; the default runnable profile uses JPA/PostgreSQL.
- No transactional outbox exists yet.
- No auth or tenant model exist yet.
- Observability features domain metrics for accepted, replayed, and rejected requests, but does not yet emit structured tracing spans.
- Account-to-ledger reconciliation requires an explicit opening-balance transaction or snapshot baseline; the current module proves transaction-level balance and defers the reconciliation worker.

## Deferred Extensions

The following capabilities are intentionally outside this module's closure boundary:

- Implement a Transactional Outbox pattern to safely publish ledger events to a message broker (e.g. Kafka).
- Refactor Spring Profiles to a centralized `@Configuration` class using `@ConditionalOnMissingBean` for cleaner bean overriding.
- Design load test scripts and run capacity estimates under high throughput.
