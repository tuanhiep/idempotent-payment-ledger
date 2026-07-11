# Architectural Notes & Design Trade-offs

This document outlines the key architectural decisions, design trade-offs, and production resiliency patterns implemented in the Idempotent Payment Ledger module.

---

## 1. Core Architectural Decisions

### 1.1. Pragmatic Persistence-Layer Coordination

**Decision**: The persistence-layer adapter (`JpaLedgerStore`) coordinates both the database transaction writes and the cache completion hooks (`afterCommit`). This introduces a pragmatic dependency between the ledger storage port (`LedgerStore`) and the idempotency cache port (`IdempotencyStore`).

**Trade-off & Rationale**:
*   **Transaction Boundary Isolation**: The synchronization of cache state with database commit boundaries requires integration with Spring's `TransactionSynchronizationManager` and Hibernate's session flush lifecycle. Keeping this coordination inside the JPA persistence adapter isolates transaction synchronization mechanics from the core business application layer (`PaymentIntakeService`).
*   **Clean Core Architecture**: By keeping transaction commit hooks out of the core application coordinator, the `PaymentIntakeService` remains technology-agnostic, database-independent, and straightforward to test in isolation.

---

## 2. Concurrency Control & Database-Level Defense-in-Depth

### 2.1. Uniqueness Guarantee (V3 Migration)
To guarantee absolute data integrity under concurrent attempts, a database-level unique constraint `uq_payments_key UNIQUE (tenant_id, idempotency_key)` is enforced on the `payments` table. This serves as the ultimate safety net if the distributed cache layer (Redis) fails or experiences key eviction.

### 2.2. Concurrency Race Recovery (Look-and-Replay)
When concurrent requests with the same idempotency key bypass the cache layer (e.g., during lock thrashing or cache eviction) and execute the write path simultaneously:
1.  **Winner Thread**: Successfully inserts the payment and commits.
2.  **Loser Thread**: Fails with a `DataIntegrityViolationException` at the database level.
3.  **Recovery Path**: The coordinator catches the uniqueness violation, rolls back the poisoned database transaction, and executes a read-only replay (`replayPayment`) in a clean session context. This resolves the race condition transparently for the caller, returning `replayed = true` and the original payment details instead of an HTTP 500 error.

---

## 3. Failure Modes & Self-Healing Resilience

### 3.1. Post-Commit Cache Failure Handling
If the database transaction commits successfully but the subsequent cache completion call (`complete()`) fails due to a network glitch or Redis timeout:
*   **Durable State**: The business state in the database is the single source of truth and is successfully committed. We charge the customer and record the ledger entries.
*   **API Response**: The request returns `ACCEPTED` (HTTP 200/201) to the client. We swallow the cache write exception and log it as a warning to prevent returning an HTTP 500 error, which could cause client-side double-charging confusion.
*   **Self-Cleaning Lock**: In the catch block of the post-commit cache update failure, we immediately trigger a `fail()` callback to release the `PROCESSING` key in the cache. This prevents the idempotency lock from getting stuck for the entire TTL window (120 seconds), allowing immediate retries.
*   **Transparent Recovery**: A subsequent retry from the client immediately hits the database Look-and-Replay path, rebuilds the Redis cache, and returns `replayed = true` cleanly.

---

## 4. Production Readiness Gaps & Future Improvements

### 4.1. Migration Safety (Deduplication Check)
Applying the V3 unique constraint to an existing production database with historical transaction data requires pre-flight audits to identify and resolve any duplicate keys. Refer to the [Operations Runbook](OPERATIONS_RUNBOOK.md) for details on pre-migration cleanup queries.

### 4.2. Load Testing & Capacity Estimation
While the core concurrency invariants are mathematically verified, future work should include running synthetic load simulations (e.g., 5,000+ concurrent requests) to benchmark Redis lock performance and compute production RAM allocation requirements based on anticipated throughput.
