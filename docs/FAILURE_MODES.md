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
- `PaymentIntakeServiceTest.sameAccountAfterTrimmingIsRejectedBeforeLedgerMutation`
- `PaymentIntakeServiceTest.amountWithMoreThanTwoDecimalPlacesIsRejectedBeforeLedgerMutation`

## Partial Commit Between Ledger And Idempotency Record

Scenario:

Ledger entries commit but the idempotency response record does not.

Expected production behavior:

Ledger entries and idempotency records must commit in one durable database transaction. If an outbox event is needed, it must be inserted in the same transaction.

Current status (Durable JPA Slice):

Fully implemented. In the `jpa` profile, both `JpaIdempotencyStore` and `JpaLedgerStore` participate in the same Spring database transaction started by `PaymentIntakeService.process()` (`REQUIRED` propagation). If ledger writing or completion fails, the entire transaction (including the idempotency state and ledger entries) rolls back atomically.

Current evidence:

- `JpaPaymentIntakeIntegrationTest.java`

## Duplicate Concurrent Requests

Scenario:

Two identical requests with the same idempotency key arrive at the same time.

Expected production behavior:

One request wins the uniqueness constraint. The other reads and returns the stored outcome or waits for in-progress completion.

Current status (Durable JPA Slice):

We have evolved from single-process in-memory coordination to database-level concurrency protection:
1. The unique constraint on `(tenant_id, idempotency_key)` in the Postgres schema acts as the ultimate concurrency boundary.
2. The losing thread gets a `DataIntegrityViolationException` on insert, which rolls back cleanly inside an isolated `REQUIRES_NEW` transaction block.
3. The losing thread then falls back to select and replay the winner's result (if `ACCEPTED`) or throws `425 Too Early` (if `PROCESSING`).

Current evidence:

- `PaymentIntakeServiceTest.concurrentDuplicateRequestsCreateOneLedgerTransaction` (In-memory verification)
- `JpaPaymentIntakeIntegrationTest.java` (Database-level verification under concurrency)

## Ledger Imbalance

Scenario:

A transaction records one side of the ledger but not the other.

Expected behavior:

The transaction must be rejected or reconciled. A valid transaction must have offsetting debit and credit entries.

Current status (Durable JPA Slice):

Implemented at the storage boundary. Both `InMemoryLedgerStore` and `JpaLedgerStore` enforce that every recorded payment generates exactly two balanced entries: a DEBIT for the payer and a CREDIT for the merchant with matching amounts and currencies. In `JpaLedgerStore`, this is performed within a single database transaction, ensuring zero risk of partial ledger writes on crash.

Current evidence:

- `PaymentIntakeServiceTest.firstPaymentCreatesBalancedLedgerEntries`
- `JpaPaymentIntakeIntegrationTest.java`

## Double-Spending / Account Balance Overdraft

Scenario:

A payer account with a balance of $10 concurrent or sequentially submits two different payments of $10 using two different idempotency keys. Since the keys are unique, both payments pass the idempotency boundary, resulting in a total debit of $20 and leaving the account with an illegal negative balance (-$10).

Expected production behavior:

The system must check the accumulated balance of the payer account before inserting new ledger entries. If `balance < request.amount`, the transaction must be rejected with an `InsufficientFundsException` and the reserved idempotency key must be cleanly failed (deleted) to allow future retries.

Current status:

Fully implemented. 
1. We enforce account-level balance validation inside `JpaLedgerStore.recordPayment()`.
2. Pessimistic Locking (`SELECT FOR UPDATE` on account rows) is performed in a strictly defined consistent locking order (by account ID sorting) to prevent deadlocks while blocking concurrent double-spending attempts.
3. A database check constraint `chk_accounts_balance_non_negative` (balance >= 0) is installed on the `accounts` table as a hard boundary defense-in-depth.

Current evidence:

- `RedisPaymentIntakeIntegrationTest.java` (Demonstrates concurrency double-spend prevention under Virtual Threads)
- `JpaPaymentIntakeIntegrationTest.java`
