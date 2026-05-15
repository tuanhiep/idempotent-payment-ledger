# Idempotent Payment Ledger

Track: `system-design`

Production-style module for retry-safe payment intake and balanced ledger mutation. The first slice models an API that accepts a payment request with an `Idempotency-Key`, persists the first outcome, replays duplicate requests with the same payload, rejects reused keys with different payloads, and records balanced debit/credit ledger entries.

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
- The current implementation is intentionally in-memory; persistence, locking, and reconciliation are production follow-ups.

## Implemented Endpoints

Run:

```bash
./mvnw -pl system-design/idempotent-payment-ledger spring-boot:run
```

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

`PaymentLedgerApplicationTests` verifies Spring context wiring.

## Production Gaps

- Idempotency records are in-memory; production requires durable storage with a uniqueness constraint.
- Ledger entries are in-memory; production requires transactional persistence.
- The current in-memory idempotency reservation is sufficient for the demo, not a distributed concurrency strategy.
- No reconciliation worker exists yet.
- No transactional outbox exists yet.
- No auth, tenant model, or risk controls exist yet.
- Observability is limited to baseline Actuator endpoints.
