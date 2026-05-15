# Engineering Narrative

## One-Minute Summary

I built an idempotent payment ledger module to demonstrate retry-safe payment intake. The system accepts a payment request with an `Idempotency-Key`, stores the first accepted outcome, replays duplicate requests with the same payload, rejects key reuse with changed payload, and records balanced debit/credit ledger entries.

## Why This Problem Matters

Payment systems fail ambiguously. A client timeout does not mean the server failed to process the request. Without idempotency, retries can double-charge users or duplicate ledger mutations.

## Hardest Technical Challenge

The hardest part is defining the correct ownership boundary for a logical payment attempt. The idempotency key must be bound to the request payload and outcome, and the ledger mutation must be atomic with the idempotency record in production.

## Main Trade-Off

The first slice uses in-memory state to prove semantics quickly. That improves learning speed and testability, but it is not production-ready. The production path requires durable storage, transaction boundaries, uniqueness constraints, and reconciliation.

## Failure Scenario

A client sends a payment, the server commits it, but the response is lost. The client retries with the same key. The correct behavior is to return the original response and avoid new ledger entries.

## How I Would Scale This 10x

- Move idempotency records and ledger entries to durable storage.
- Add a uniqueness constraint for idempotency keys.
- Scope keys by tenant/account.
- Add in-progress status for long-running requests.
- Add reconciliation jobs.
- Emit outbox events for downstream processing.
- Add metrics for accepted, replayed, conflict, and invalid requests.

## How I Would Operate This In Production

I would alert on elevated idempotency conflicts, ledger imbalance, reconciliation failures, and outbox lag. I would also expose dashboards for accepted payments, replay ratio, conflict ratio, and persistence latency.

## What I Learned

Idempotency is not just "return the same response." It is a contract around logical operation identity, payload binding, transaction boundaries, and failure recovery.

## Staff+/Principal Signal

This module shows correctness-first thinking: naming invariants, rejecting ambiguous key reuse, modeling balanced ledger entries, documenting production gaps, and designing the path from demo semantics to durable architecture.

## Possible Interview Questions

- How would you make this safe across multiple API instances?
- What if the first request is still processing when the retry arrives?
- How long should idempotency records live?
- How would you reconcile payment records and ledger entries?
- Why use a database uniqueness constraint instead of Redis locks?
