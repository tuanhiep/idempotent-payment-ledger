# Operations Runbook - Idempotent Payment Ledger

This document defines the operational procedures, failure recovery runbooks, and reconciliation steps for the Idempotent Payment Ledger.

## 🏛️ System Architecture Overview

The system runs on a hybrid database architecture:
1.  **Redis (Idempotency Store)**: The high-speed outer boundary caching layer. Uses `SETNX` for atomic lock reservation (`PROCESSING`) and caches successful results (`ACCEPTED`) with a 7-day TTL.
2.  **PostgreSQL (Ledger Store)**: The authoritative double-entry bookkeeping ledger. Holds accounts, balances, payments, and balanced ledger transactions (debit/credit entries).
3.  **Spring Boot Application (PaymentIntakeService)**: The Non-Transactional Coordinator orchestrating boundary checks, balance locking, and transaction commits.

---

## 🚨 Infrastructure Failure Runbooks

### 1. PostgreSQL Database Outage (P0)

*   **Symptoms**: 
    *   API returns HTTP `500 Internal Server Error` with `CannotGetJdbcConnectionException` or `HikariPool-1 - Connection is not available`.
    *   Micrometer metric `payment.intake.requests{status="failed"}` spikes.
*   **Impact**: Core payment intake is entirely blocked. The application cannot write payments or mutate ledger balances.
*   **Runbook Actions**:
    1.  Verify DB container/host status: `docker ps` or verify AWS RDS instance status.
    2.  If connection pool exhaustion is suspected, inspect active connections:
        ```sql
        SELECT pid, age(clock_timestamp(), query_start), usename, state, query 
        FROM pg_stat_activity 
        WHERE state != 'idle' AND query NOT LIKE '%pg_stat_activity%';
        ```
    3.  Restart database or scale connection pool size if required by adjusting `spring.datasource.hikari.maximum-pool-size` configuration.
    4.  Note: Redis keys marked `PROCESSING` will naturally expire after 120 seconds. Clients attempting retry during DB outage will receive HTTP `425 Too Early` or HTTP `500`, then automatically transition to clean retries once Postgres recovers.

### 2. Redis Cluster Outage / Cache Eviction (P1)

*   **Symptoms**: 
    *   API returns HTTP `500` or fails to connect to Redis (`RedisConnectionFailureException`).
    *   Prometheus alert triggers for Redis memory exhaustion or service down.
*   **Impact**: External boundary locking is disabled. Concurrency race protection falls back entirely to PostgreSQL unique constraints.
*   **Fallback Mode (JPA-Only Execution)**:
    If Redis suffers a prolonged outage, the system can be configured to run in **JPA-Only Mode** (bypassing Redis and relying on PostgreSQL for both idempotency locks and ledger writes).
    *   **Action**: Change the Spring Boot active profile from `jpa,redis` to `jpa` and redeploy:
        ```bash
        # In application.properties or environment variables:
        spring.profiles.active=jpa
        ```
        *This wires JpaIdempotencyStore which uses PostgreSQL `idempotency_records` table to enforce single-process/distributed idempotency.*
*   **Recovery after Redis Restored**:
    1.  Bring Redis back online.
    2.  Restart Redis profile: `spring.profiles.active=jpa,redis`.
    3.  If Redis cache was wiped during restart, the **DB Look-and-Replay** path in `JpaLedgerStore` will automatically handle cache misses by checking PostgreSQL, validating request payload consistency, and rebuilding the Redis cache on-the-fly.

### 3. Network Partitioning / Redis Split-Brain (P2)

*   **Symptoms**: Two application nodes talk to two different Redis masters, leading to duplicate concurrent requests bypassing the Redis lock boundary.
*   **Impact**: Redundant requests reach the database layer simultaneously.
*   **Validation & Resolution**:
    *   PostgreSQL will act as the hard boundary defense. The second transaction attempting to commit the same idempotency key will trigger a unique key violation:
        `ERROR: duplicate key value violates unique constraint "uq_payments_key"`
    *   The transaction is safely rolled back in Postgres; no double-spend occurs.
    *   Check application logs for `DataIntegrityViolationException`. Resolve the network partition at the infrastructure/routing layer.

---

## 🛠️ Troubleshooting & Manual Interventions

### 1. Stuck `PROCESSING` State (Redis Lock Leak)

If a Spring Boot container crashes abruptly *after* reserving a key in Redis but *before* completing or failing the transaction, the Redis key remains stuck as `PROCESSING` for up to 120 seconds.
*   **Client Response**: HTTP `425 Too Early` ("payment is still being processed").
*   **Automatic Resolution**: The lock will automatically self-expire after 120 seconds (Redis TTL).
*   **Manual Intervention (Emergency Force-Unlock)**:
    If a P0 merchant transaction is blocked and cannot wait 120 seconds:
    1.  Connect to the Redis instance: `redis-cli`.
    2.  Locate the stuck key safely using `SCAN` to avoid blocking production: 
        `SCAN 0 MATCH idempotency:* COUNT 1000`
    3.  Delete the key: `DEL idempotency:<stuck_key>`.
    4.  Ask the client to retry immediately.

### 2. Payload Fingerprint Mismatch Conflict (409)

*   **Symptom**: Client receives HTTP `409 Conflict` ("idempotency key already exists for a different payment payload").
*   **Cause**: The client reused an existing key but changed parameters (e.g. payer, amount, currency), which is an API violation.
*   **Action**: Trace client request parameters in logs. Inspect database payments to see what was originally recorded:
    ```sql
    SELECT payment_id, payer_account_id, merchant_account_id, amount, currency, status 
    FROM payments 
    WHERE idempotency_key = 'offending-key';
    ```
    Confirm that the client needs to generate a fresh, unique `Idempotency-Key` for the new transaction.

---

## 📊 Reconciliation & Ledger Audit Runbook

To ensure the double-entry bookkeeping ledger is healthy, run the following SQL audits daily or weekly:

### 1. General Ledger Balance Audit (Double-Entry Invariant)

Every ledger transaction must have offsetting credit and debit entries that sum to exactly `0.00`. If this query returns any records, the ledger is imbalanced.
```sql
SELECT transaction_id, SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) AS balance
FROM ledger_entries
GROUP BY transaction_id
HAVING SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END) != 0.00;
```
*   **Expected Result**: Empty set.
*   **Action if Triggered**: Halt active transaction settlements. Trace database logs for partial writes.

### 2. Database Balance vs Ledger Entry Audit

Verify that the stored account balance matches the sum of its credit and debit ledger entries, taking into account the initial seeded balances for test accounts (1000.00 for `acct-payer` and `acct-payer-http`).
```sql
SELECT 
    a.account_id,
    a.balance AS current_stored_balance,
    (
        CASE 
            WHEN a.account_id IN ('acct-payer', 'acct-payer-http') THEN 1000.0000 
            ELSE 0.0000 
        END
        + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0.0000)
    ) AS calculated_ledger_balance
FROM accounts a
LEFT JOIN ledger_entries le ON a.account_id = le.account_id
GROUP BY a.account_id, a.balance
HAVING a.balance != (
    CASE 
        WHEN a.account_id IN ('acct-payer', 'acct-payer-http') THEN 1000.0000 
        ELSE 0.0000 
    END
    + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0.0000)
);
```
*   **Expected Result**: Empty set.
*   **Action if Triggered**: Out-of-band balance reconciliation is required. Freeze the affected account and verify if manual balance correction is needed.
