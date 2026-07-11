# Operations Runbook - Idempotent Payment Ledger

This document defines the operational procedures, failure recovery runbooks, and reconciliation steps for the Idempotent Payment Ledger.

## 🏛️ System Architecture Overview

The module supports a PostgreSQL-only profile and an optional PostgreSQL-plus-Redis profile:
1.  **Redis (Optional Idempotency Store)**: The outer reservation/cache layer used by the
    `jpa,redis` profile. It uses `SETNX` to reserve `PROCESSING`, carries a per-owner token,
    and transitions state only through atomic compare operations. Successful responses
    are cached as `ACCEPTED` for 7 days.
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
    4.  Redis keys marked `PROCESSING` naturally expire after 120 seconds. A request that
        successfully cleans up its own reservation may permit an earlier retry; otherwise
        clients receive `425 Too Early` until lease expiry. Database errors remain retryable
        failures and must not be interpreted as proof that no payment committed.

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
*   **Manual Intervention (Emergency Owner-Safe Unlock)**:
    Prefer waiting for the 120-second TTL. If a critical transaction cannot wait:
    1.  Connect to the Redis instance: `redis-cli`.
    2.  Locate the stuck key safely using `SCAN` to avoid blocking production:
        `SCAN 0 MATCH idempotency:* COUNT 1000`
    3.  Read the exact value and TTL using `GET idempotency:<stuck_key>` and
        `PTTL idempotency:<stuck_key>`.
    4.  Query PostgreSQL for the same idempotency key. If a payment exists, do not unlock;
        use look-and-replay or repair the cache from the durable outcome.
    5.  Confirm from logs that the owning request is no longer running.
    6.  Delete only if the value has not changed since step 3:
        ```redis
        EVAL "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end" 1 "idempotency:<stuck_key>" "<exact-value-from-step-3>"
        ```
    7.  A result of `0` means ownership changed; stop and investigate. A result of `1`
        permits the client to retry.

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

### 2. Fixture Balance Diagnostic

The current module initializes local demo balances outside the ledger. Therefore an
account-level reconstruction requires an explicit baseline and the following query is
valid only for the documented local fixture (`acct-payer` starts at `1000.00`). It is not a
production reconciliation proof.
```sql
SELECT
    a.account_id,
    a.balance AS current_stored_balance,
    (
        CASE
            WHEN a.account_id = 'acct-payer' THEN 1000.0000
            ELSE 0.0000
        END
        + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0.0000)
    ) AS calculated_ledger_balance
FROM accounts a
LEFT JOIN ledger_entries le ON a.account_id = le.account_id
GROUP BY a.account_id, a.balance
HAVING a.balance != (
    CASE
        WHEN a.account_id = 'acct-payer' THEN 1000.0000
        ELSE 0.0000
    END
    + COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE -le.amount END), 0.0000)
);
```
*   **Expected Result**: Empty set.
*   **Action if Triggered**: Freeze the affected account and investigate. Do not update a
    balance in place as an accounting repair; use an auditable compensating transaction.
*   **Production requirement**: Represent opening balance as a ledger transaction or store
    a versioned snapshot baseline. Implement the reconciliation worker before using this
    model for real settlement.

---

## 🚀 Pre-Flight & Schema Rollout Guide

### ⚠️ Pre-Flight Check for Duplicate Payments

Before executing the **V3 unique constraint migration** (`V3__add_unique_constraint_payments.sql`) in a live production database with existing transaction history:

1.  **Detect Duplicate Rows**:
    Check if any duplicate combinations of `(tenant_id, idempotency_key)` exist:
    ```sql
    SELECT tenant_id, idempotency_key, COUNT(*)
    FROM payments
    GROUP BY tenant_id, idempotency_key
    HAVING COUNT(*) > 1;
    ```
2.  **Mitigation & Incident Response Protocol**:
    If duplicate combinations are detected, applying the `V3` unique constraint will fail, halting the release pipeline. **DO NOT attempt to delete transaction history directly from the database.** Deleting historical rows will violate audit logs and database foreign key integrity (e.g., from ledger entries).

    Instead, execute the following Incident Response protocol:
    - **Halt Rollout**: Immediately abort the database migration and notify the on-call and release manager teams.
    - **Analyze & Locate Winner**: Identify the duplicate entries and determine which database row is the canonical payment (e.g., matching the success status returned to the customer or payment gateway).
    - **Rekey Conflicting Entries**: For the duplicates that are non-canonical, update the `idempotency_key` column to append a suffix (e.g., `_duplicate_v3_migration_incident_<incident_id>`). This preserves audit logs, honors foreign keys, and satisfies the physical uniqueness constraint:
      ```sql
      -- Example suffix rekeying query (safe; preserves historical data for audits)
      UPDATE payments
      SET idempotency_key = idempotency_key || '_dup_incident_12345'
      WHERE payment_id = '<non_canonical_payment_id>';
      ```
    - **Audit & Reconcile**: Verify that the associated ledger transactions and entries remain balanced. Execute compensating accounting entries if any discrepancy is found.

### V4 Test-Account Cleanup

V4 removes only known fixture accounts that have no referencing ledger entries. Historical
accounts already used by ledger history are preserved. The migration upgrade test covers
the V1-V3 to V4 path with existing payment and ledger data.

Before rollout, report any preserved fixture accounts:

```sql
SELECT account_id
FROM accounts
WHERE tenant_id = 'default'
  AND account_id IN ('acct-payer', 'acct-merchant', 'acct-payer-http', 'acct-merchant-http');
```

Do not delete returned accounts if ledger entries reference them. Treat them as historical
data and rename/classify them only through an audited migration plan.

### Redis Owner-Token Rollout

Older application versions wrote `PROCESSING:<payloadHash>` without an owner token. New
readers tolerate that legacy value until it expires, but owner-safe completion requires all
writers to run the new protocol. Drain old instances, then wait at least one processing TTL
(120 seconds) before declaring the owner-token rollout complete.
