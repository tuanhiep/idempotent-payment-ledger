# Engineering Narrative

## The Problem Worth Solving

Payment APIs exist at the intersection of money and network unreliability. A client submits a payment, the server processes it, and then — the network drops. Did the charge go through? The client doesn't know. It retries.

If the server charged on the first request and charges again on the retry, the payer is double-charged. If it refuses all retries, legitimate customers hit dead ends. If it accepts retries but doesn't recognize duplicates, ledger state becomes inconsistent.

Idempotency is the standard answer. But production idempotency is significantly harder than it looks.

---

## What Most Implementations Get Wrong

The tutorial version of payment idempotency is:

1. Generate a unique `Idempotency-Key` on the client.
2. On the server, check if the key exists in a store.
3. If yes, return the stored response. If no, process and store.

This breaks in at least three ways in production:

**Race condition under concurrent retries.** If two retries arrive simultaneously before either has a stored response, both pass the "does key exist?" check and both proceed to charge. The result: duplicate ledger entries with no idempotency protection at all.

**Cache-ahead-of-commit.** If a Redis-backed implementation marks a payment `ACCEPTED` during processing, but the database transaction rolls back a millisecond later, the cache now lies. The next retry sees `ACCEPTED` and returns a successful response for a payment that never committed.

**Stale owner stomping replacement leases.** If a processing reservation expires mid-flight and a replacement request acquires the lease, the original request's cleanup call (`fail()`) must not evict the new owner — or the replacement is silently left in a stuck `PROCESSING` state.

This module addresses all three.

---

## The Design

### Outer Boundary: Redis Reservation with Owner Tokens

The first line of defense is a Redis `SETNX` (set-if-not-exists) reservation. When a request arrives, the system atomically acquires a `PROCESSING` lease keyed by `idempotency:K`, including an owner token unique to this reservation:

```
SETNX idempotency:K → PROCESSING:<payloadHash>:<ownerToken>
```

Only the winning thread proceeds. Concurrent duplicates see `PROCESSING` immediately and return `425 Too Early` to the caller. This keeps the database entirely out of the hot concurrent-duplicate path.

### Inner Boundary: PostgreSQL as Source of Truth

Redis is fast but volatile. PostgreSQL is the authoritative correctness boundary. The `payments` table carries a `UNIQUE (tenant_id, idempotency_key)` constraint (Flyway V3). If Redis state is evicted, a lease expires, or duplicate work otherwise bypasses the perimeter, PostgreSQL rejects a second insert for the same key with a constraint violation — which the application handles as a replay, not an error. A total Redis connectivity outage fails the hybrid request at the perimeter; operations must restore Redis or deliberately switch to the JPA-only profile.

The current posting path generates a matched debit and credit, then commits both entries atomically with the payment record in one database transaction. The transaction boundary prevents a crash from committing only one of those writes. PostgreSQL does not yet contain a generalized cross-row constraint proving that arbitrary future posting rules sum to zero; reconciliation and a future posting-rule validator remain explicit safeguards.

### Commit-Boundary Coordination: afterCommit Hook

The most subtle correctness requirement: Redis must not receive `ACCEPTED` until the database transaction has durably committed.

This is implemented via Spring's `TransactionSynchronizationManager.registerSynchronization().afterCommit()`. The `complete()` call that updates the Redis cache is deferred to the post-commit hook. If the database rolls back, `afterCommit` never publishes `ACCEPTED`. The application then attempts an owner-safe cleanup of its `PROCESSING` reservation so a retry can re-enter the write path; if the process crashes before cleanup, the Redis lease remains only until its TTL expires.

The `IdempotencyStore` interface exposes `requiresAfterCommitCompletion()` so the JPA adapter (which runs `complete()` inside the same transaction) and the Redis adapter (which must run it after) can each declare the correct behavior without the coordinator needing to know about adapter types.

### Owner-Safe Cleanup: Atomic Lua Compare-and-Delete

When a payment fails (e.g. `InsufficientFundsException`), the system must release the `PROCESSING` lease so future retries get a clean slate. But if the TTL had already expired and a replacement request has acquired a new lease, the original request's `fail()` call must not delete the replacement.

This is enforced with a Lua script that checks the owner token before deleting:

```lua
if redis.call("GET", key) == expected then
    return redis.call("DEL", key)
end
return 0
```

Same pattern for `complete()`: the Lua script checks the owner token before writing `ACCEPTED`. A stale owner that lost its lease cannot overwrite the replacement's `PROCESSING` entry.

### Recovery: DB Look-and-Replay

When the Redis cache is empty (TTL expiry, restart, flush) but a payment already exists in the database, the system recovers transparently. On a cache miss with a `NewReservation`, the ledger store checks PostgreSQL before writing:

1. If a matching payment exists with a consistent payload → rebuild the Redis cache and return `replayed=true`.
2. If a matching payment exists with a different payload → `409 Conflict`.
3. If nothing exists → proceed with the normal write path.

This recovery path is exercised by a dedicated integration test that forces a Redis `complete()` failure, confirms the database committed, then retries and verifies the replay response.

---

## What the Tests Prove

The primary implemented correctness claims below have corresponding integration tests running against real PostgreSQL 16 and Redis 7 containers via Testcontainers:

| Claim | Test |
|---|---|
| First payment creates balanced ledger entries | `firstPaymentPersistsBalancedLedgerEntriesInPostgresAndCacheInRedis` |
| Duplicate replay served from Redis cache | `duplicateSamePayloadReturnsReplayDirectlyFromRedis` |
| Concurrent double-spend prevented at DB level | `concurrentDoubleSpendingPreventedByDurablePostgresLockAndRedisIdempotency` |
| Redis not updated to ACCEPTED on DB rollback | `whenDatabaseTransactionRollsBack_thenRedisIsNotUpdatedToAccepted` |
| DB Look-and-Replay recovers from Redis failure | `whenRedisCommitFailsButDatabaseCommits_thenPaymentIsNotLostAndRetryRecoversViaDbReplay` |
| Stale owner cannot delete replacement lease | `staleReservationCannotDeleteAReplacementOwner` |
| Stale owner cannot complete over replacement | `staleReservationCannotCompleteOverAReplacementOwner` |
| V4 migration preserves historical account data | `v4CleanupPreservesHistoricalAccountsReferencedByLedgerEntries` |

The CI pipeline runs the full suite on every push to `main`.

---

## What This Does Not Claim

- A durable JPA `PROCESSING` reservation is not automatically reclaimed after process death; the schema stores `expires_at`, but cleanup and fencing semantics remain deferred.
- PostgreSQL transaction atomicity does not replace a generalized posting-rule validator or a cross-row balance proof.
- The project does not yet implement authentication, tenant isolation, transactional outbox delivery, a reconciliation worker, or measured throughput/capacity.
- This is a production-shaped correctness case study, not a claim that the service is ready to move real money.

---

## Trade-offs Made Explicit

**Redis as outer boundary, not source of truth.** Redis provides fast reservation and replay-from-cache. It is not the authority. If Redis loses data, the PostgreSQL constraint and DB Look-and-Replay maintain correctness. This avoids the failure mode where Redis becomes a single point of truth for financial state.

**Non-transactional coordinator.** `PaymentIntakeService.process()` carries no `@Transactional` annotation. The database transaction is opened and closed inside the ledger adapter, not the application service. This prevents holding a database connection open during any future external I/O (e.g. calling Stripe, a risk engine, or a fraud service) — a common cause of connection pool exhaustion under load.

**Pessimistic locking with consistent lock order.** Account rows are locked with `SELECT FOR UPDATE` in alphabetical account ID order before any balance mutation. This prevents deadlock between concurrent cross-transfers without requiring application-level distributed locks.

**Deferred: transactional outbox, auth, reconciliation worker.** Payment event publishing (Kafka/outbox), authentication/tenant isolation, and the account reconciliation poller are explicitly deferred. These are not oversights — they are the natural next slices after proving the correctness kernel.

---

## What This Demonstrates

This module is not a showcase of volume. It is a showcase of reasoning under correctness constraints:

- Identifying the failure modes that matter (cache-ahead-of-commit, concurrent race, stale lease)
- Choosing the right tool for each boundary (Redis for speed, PostgreSQL for durability)
- Keeping the application layer technology-agnostic while the infrastructure adapters own their mechanics
- Mapping implemented correctness claims to failure-path evidence while naming the boundaries that remain deferred
