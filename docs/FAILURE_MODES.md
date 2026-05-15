# Failure Modes

## Duplicate Retry After Client Timeout

Scenario:

The server accepts and records a payment, but the client times out before receiving the response.

Expected behavior:

The client retries with the same `Idempotency-Key` and same payload. The system returns the stored response and does not create new ledger entries.

Current evidence:

- `PaymentIntakeServiceTest.duplicateRequestWithSamePayloadReturnsStoredResponseWithoutNewLedgerEntries`
- `PaymentControllerIntegrationTest.duplicateHttpRequestIsReplayed`

## Idempotency Key Reused With Different Payload

Scenario:

A caller reuses an existing key with a changed amount, payer, merchant, or currency.

Expected behavior:

The system rejects the request with `409 Conflict`.

Current evidence:

- `PaymentIntakeServiceTest.duplicateKeyWithDifferentPayloadIsRejected`
- `PaymentControllerIntegrationTest.duplicateHttpKeyWithDifferentPayloadReturnsConflict`

## Invalid Request Before Mutation

Scenario:

The caller sends an invalid amount or malformed logical request.

Expected behavior:

The system rejects the request before mutating ledger state.

Current evidence:

- `PaymentIntakeServiceTest.invalidAmountIsRejectedBeforeLedgerMutation`

## Partial Commit Between Ledger And Idempotency Record

Scenario:

Ledger entries commit but the idempotency response record does not.

Expected production behavior:

Ledger entries and idempotency records must commit in one durable database transaction. If an outbox event is needed, it must be inserted in the same transaction.

Current status:

Not implemented. The first slice uses an in-memory idempotency reservation to demonstrate semantics, not durable failure recovery.

## Duplicate Concurrent Requests

Scenario:

Two identical requests with the same idempotency key arrive at the same time.

Expected production behavior:

One request wins the uniqueness constraint. The other reads and returns the stored outcome or waits for in-progress completion.

Current status:

Single-process duplicate protection uses `InMemoryIdempotencyStore` to reserve an idempotency key before ledger mutation. The winner completes the stored outcome; duplicate same-payload requests replay it. Distributed concurrency is a future persistence slice.

Current evidence:

- `PaymentIntakeServiceTest.concurrentDuplicateRequestsCreateOneLedgerTransaction`

## Ledger Imbalance

Scenario:

A transaction records one side of the ledger but not the other.

Expected behavior:

The transaction must be rejected or reconciled. A valid transaction must have offsetting debit and credit entries.

Current evidence:

- `PaymentIntakeServiceTest.firstPaymentCreatesBalancedLedgerEntries`
