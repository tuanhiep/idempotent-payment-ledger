# Gate Checklist

## Correctness

- [x] Main invariants are named.
- [x] Duplicate retry with same idempotency key and payload returns the stored response.
- [x] Concurrent duplicate retry with the same idempotency key creates one ledger transaction in a single process.
- [x] Duplicate idempotency key with changed payload is rejected.
- [x] Ledger entries for one transaction balance to zero.
- [x] Invalid amount is rejected before ledger mutation.
- [x] Same account after canonical trimming is rejected before ledger mutation.
- [x] Amount scale violations return domain validation errors instead of leaking arithmetic failures.

## Architecture

- [x] Initial design doc exists.
- [x] Goals and non-goals are explicit.
- [x] First trade-offs are documented.
- [x] Durable storage alternatives are compared.
- [x] Transaction boundary is represented with a production persistence design.
- [x] Postgres Flyway migration exists with tables, constraints, and indexes (`src/main/resources/db/migration/V1__init_payment_ledger.sql`).
- [x] ADR-001 documents persistence design decision and lock strategy.
- [x] JPA persistence adapters replace in-memory adapters (`JpaIdempotencyStore`, `JpaLedgerStore`).

## Implementation

- [x] Module is wired into the Maven reactor.
- [x] Spring Boot application starts.
- [x] HTTP API exists.
- [x] Service-level tests cover core behavior.
- [x] Service-level concurrent duplicate test covers in-memory idempotency reservation.
- [x] HTTP integration tests cover duplicate/replay behavior.

## Operations

- [x] Baseline Actuator endpoints are configured.
- [x] Domain metrics exist for accepted, replayed, and rejected requests.
- [x] Runbook exists.

## Scale

- [x] Scale assumptions are documented.
- [ ] Load test exists. (Deferred to high-throughput performance tuning phase; see docs/ARCHITECT_NOTES.md)
- [ ] Capacity estimate exists. (Deferred to high-throughput performance tuning phase; see docs/ARCHITECT_NOTES.md)

## Failure

- [x] Initial failure modes are documented.
- [x] Ledger imbalance limitation and next slice are documented.
- [x] Recovery path is implemented for reconciliation.
- [x] Timeout-after-commit scenario is simulated. (Verified via integration tests in RedisPaymentIntakeIntegrationTest)

## Security

- [x] Trust boundary for idempotency keys is documented. (Documented in docs/DESIGN_DOC.md)
- [x] Tenant/auth model is documented. (Documented in docs/DESIGN_DOC.md)

## Engineering Communication

- [x] Engineering narrative exists.
- [x] Key design trade-offs are summarized for design review. (See docs/ARCHITECT_NOTES.md)
- [x] Production gaps are explicitly documented with next steps. (See docs/ARCHITECT_NOTES.md)

## Engineering Review

- [x] Architect notes exist.
- [x] Implementation assumptions and review findings are documented after first red-team pass. (See docs/ARCHITECT_NOTES.md)
