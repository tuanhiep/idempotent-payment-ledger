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

## Implementation

- [x] Module is wired into the Maven reactor.
- [x] Spring Boot application starts.
- [x] HTTP API exists.
- [x] Service-level tests cover core behavior.
- [x] Service-level concurrent duplicate test covers in-memory idempotency reservation.
- [x] HTTP integration tests cover duplicate/replay behavior.

## Operations

- [x] Baseline Actuator endpoints are configured.
- [ ] Domain metrics exist for accepted, replayed, and rejected requests.
- [ ] Runbook exists.

## Scale

- [x] Scale assumptions are documented.
- [ ] Load test exists.
- [ ] Capacity estimate exists.

## Failure

- [x] Initial failure modes are documented.
- [x] Ledger imbalance limitation and next slice are documented.
- [ ] Recovery path is implemented for reconciliation.
- [ ] Timeout-after-commit scenario is simulated.

## Security

- [ ] Trust boundary for idempotency keys is documented.
- [ ] Tenant/auth model is documented.

## Narrative

- [x] Interview narrative exists.
- [ ] CV bullet is derived from this module.

## AI Ownership

- [x] Architect notes exist.
- [ ] AI mistakes and corrections are logged after first red-team pass.
