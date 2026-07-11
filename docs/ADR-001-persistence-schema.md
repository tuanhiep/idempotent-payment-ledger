# ADR-001: Postgres Persistence Schema and Transaction Boundary

**Status:** Accepted
**Date:** 2026-05-25
**Deciders:** Principal Engineer

## Context

The first slice uses in-memory state to prove idempotency semantics. The next
slice must introduce durable storage so the correctness invariants hold across
process restarts, retries from multiple API instances, and crash recovery.

The core invariant is:

```
One logical payment attempt maps to one accepted outcome and one balanced
ledger transaction, regardless of how many times the client retries.
```

This requires answering three questions before writing any JPA code:

1. What is the transaction boundary — what commits atomically?
2. How do concurrent requests with the same idempotency key race safely?
3. What indexes are needed to keep the idempotency lookup and account queries fast?

## Decision

Use a **single Postgres transaction** per accepted payment that commits
five writes atomically:

```
INSERT idempotency_records  (status = PROCESSING — claims the key)
INSERT payments
INSERT ledger_transactions
INSERT ledger_entries x2    (balanced debit + credit)
INSERT outbox_events        (optional domain event)
UPDATE idempotency_records  (status = ACCEPTED, stores response body)
```

Use a **UNIQUE constraint** on `(tenant_id, idempotency_key)` as the primary
concurrency control. Two concurrent requests race on INSERT; one wins, one
receives a unique constraint violation and falls back to reading the existing
record.

Full DDL is in `src/main/resources/db/migration/V1__init_payment_ledger.sql`.

## Options Considered

### Option A: Single Postgres transaction with UNIQUE constraint (chosen)

| Dimension | Assessment |
|---|---|
| Correctness | High — DB enforces uniqueness; no split-brain possible |
| Concurrency | Handled by INSERT race + constraint violation fallback |
| Complexity | Low — one transaction, one source of truth |
| Operability | High — standard Postgres tooling, no extra infra |
| Testability | High — exercised by PostgreSQL Testcontainers with the same Flyway migration used locally |

**Pros:** atomic by default, uniqueness is enforced at DB level, no extra
infrastructure, matches how production payment systems actually work.

**Cons:** long-running transactions increase lock contention under high
throughput; idempotency record holds a row lock while PROCESSING.

### Option B: Redis lock + Postgres write

| Dimension | Assessment |
|---|---|
| Correctness | Medium — Redis success does not guarantee DB success |
| Concurrency | Handled by Redis SETNX before DB write |
| Complexity | High — two systems, two failure domains |
| Operability | Medium — Redis TTL and DB uniqueness must stay in sync |

**Pros:** reduces DB pressure for high-collision keys; lock TTL is configurable.

**Cons:** Redis failure leaves the DB without a guard if not backed by database unique constraints; requires two-phase reasoning across systems; does not replace the DB uniqueness constraint anyway.
**Evolved Decision:** Evolved from Option A to Option B in the second slice to support high scale under Virtual Threads. By coupling the Redis `SETNX` lock boundary with PostgreSQL unique constraints and look-and-replay recovery, we eliminate database connection pool exhaustion while maintaining strict correctness.

### Option C: Optimistic locking (version column)

Appropriate for update-heavy scenarios. Not the right fit here because the
idempotency record is written once as PROCESSING and updated once to ACCEPTED.
Optimistic locking adds a retry loop without simplifying the race condition.
Deferred.

### Option D: Event-sourced ledger as first persistence slice

Powerful for audit and temporal queries. Adds event store modeling and
projection complexity before the basic transaction boundary is proven.
Deferred to a future module.

## Trade-off Analysis

We chose a hybrid architecture:
1. **At the outer edge**: Option B (Redis cache-aside) acts as the high-throughput locking boundary to throttle concurrent requests and prevent DB connection starvation.
2. **At the persistence layer**: Option A (PostgreSQL) acts as the authoritative correctness boundary. We enforce unique constraints (`uq_payments_key`), pessimistic locking (`SELECT FOR UPDATE`), and balance check constraints (`balance >= 0`) inside the database.

The key trade-off is network latency versus connection pool utilization. By separating the distributed lock boundary (Redis) from the authoritative transactional boundaries (Postgres), we reduce database transaction times to ~5ms, at the operational cost of requiring post-commit synchronization and a look-and-replay recovery path in case of Redis cache eviction or failure.

## Consequences

**Becomes easier:**
- Retry safety is enforced by the DB constraint, not by application code.
- Crash recovery is automatic: uncommitted transactions roll back; PROCESSING
  records with no completed_at indicate in-flight requests that need resolution.
- Reconciliation job can audit balance by querying `ledger_entries` directly.

**Becomes harder:**
- Long PROCESSING records (from a crashed API instance) block retries until
  a cleanup job or timeout clears them.
- Schema migrations on `idempotency_records` require care because the table
  is on the hot write path.

**Must revisit:**
- PROCESSING timeout and cleanup strategy (crash recovery path).
- Tenant scoping when multi-tenancy is introduced.
- Outbox poller implementation and at-least-once delivery guarantees.
- Index maintenance cost under high insert throughput.

## Action Items

- [x] Define full DDL in `src/main/resources/db/migration/V1__init_payment_ledger.sql`
- [x] Document transaction boundary in `docs/DESIGN_DOC.md`
- [x] Implement `JpaIdempotencyStore` using `DataIntegrityViolationException` fallback (equivalent to INSERT ... ON CONFLICT)
- [x] Implement `JpaLedgerStore` inside the same `@Transactional` boundary
- [x] Add `@Transactional` integration test covering duplicate replay via `JpaPaymentIntakeIntegrationTest`
- [x] Add reconciliation query test verifying ledger balance invariant via SQL (`ledgerBalanceInvariantVerifiedByReconciliationQuery`)
- [ ] Define PROCESSING timeout and cleanup strategy
