# Architect Notes

## Original Intent

Create the first flagship `infra` module around correctness under failure: idempotent payment intake with balanced ledger entries.

## Key Architectural Decisions

- Start with a small in-memory implementation to prove semantics before introducing persistence.
- Bind idempotency keys to a payload hash, not only to a stored payment id.
- Make duplicate same-payload requests return the original outcome.
- Make duplicate changed-payload requests fail with `409 Conflict`.
- Model the ledger as balanced debit and credit entries.
- Use atomic in-memory idempotency reservation instead of a coarse service-level critical section.

## AI-Assisted Implementation Strategy

AI is used as an implementation accelerator for scaffolding, test generation, and documentation drafts.

Human-owned decisions:

- module selection;
- correctness invariants;
- idempotency conflict semantics;
- double-entry ledger shape;
- avoiding coarse application-level locking as a public demo concurrency boundary;
- public docs without numeric prefixes;
- first-slice scope.

## Human Review Checklist

- Does a duplicate request create new ledger entries?
- Does reused key with changed payload fail?
- Does invalid input mutate state?
- Are debit and credit entries balanced?
- Is the production gap explicit?

## Mistakes Found In AI Output

- Initial implementation used a coarse service-level critical section in the payment intake path. That demonstrates single-process serialization but reads poorly for a Java 21 / virtual-thread-aware public artifact, and it would become dangerous if blocking persistence were added inside the critical path.

## Corrections Applied

- Replaced the service-level critical section with an `IdempotencyStore` abstraction. The completed idempotency outcome is immutable; in-progress coordination is hidden inside the in-memory store.
- Added a concurrent duplicate-request service test using virtual threads.
- Kept durable database uniqueness constraints as the production correctness boundary.
- Documented the durable transaction boundary as the next slice before implementing persistence code.

## Final Engineering Judgment

The first slice is intentionally narrow. It is not production-ready, but it establishes the module's core invariant: a logical payment attempt maps to one accepted outcome and one balanced ledger transaction.

## What I Would Change In Production

- Use durable storage.
- Add a uniqueness constraint on idempotency key scoped by tenant/account.
- Store in-progress records to handle long-running calls.
- Insert payment, ledger entries, idempotency record, and outbox event in one transaction.
- Introduce an explicit posting rule boundary and validate ledger balance before persistence.
- Add reconciliation jobs.
- Add domain metrics and alerts.
- Add auth, tenant isolation, and fraud/risk checks.

## What I Would Ask In A Design Review

- How long are idempotency records retained?
- Is the key scoped by tenant, account, merchant, or global namespace?
- What happens if processing is still in progress during a retry?
- How does reconciliation detect incomplete ledger transactions?
- Which state transitions are allowed after `ACCEPTED`?

## What This Module Demonstrates

Correctness-first system design, idempotency semantics, ledger invariants, and a disciplined path from simple executable proof to production architecture.
