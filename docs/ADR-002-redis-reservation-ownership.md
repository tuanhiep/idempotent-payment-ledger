# ADR-002: Owner-Scoped Redis Reservations

**Status:** Accepted  
**Date:** 2026-07-10

## Context

A Redis idempotency reservation is a lease, not permanent ownership. Request A can pause
long enough for its TTL to expire, after which request B can acquire the same key. If A
later executes an unconditional `DEL` or `SET`, it can destroy or overwrite B's state.
Database uniqueness still protects the durable payment, but the Redis coordination layer
would no longer preserve reservation ownership.

## Decision

Every new reservation receives a random `ownerToken`. The Redis processing value is:

```text
PROCESSING:<payloadHash>:<ownerToken>
```

Reservation transitions are atomic Lua operations:

- `complete`: replace `PROCESSING` with `ACCEPTED` only when the entire current value
  matches the caller's reservation;
- `fail`: delete the key only when the entire current value matches the caller's
  reservation;
- a stale owner receives no authority over a replacement reservation.

PostgreSQL remains the source of truth. If owner-safe cache completion cannot occur after
the database commits, the API still returns the durable result and a later retry uses the
database look-and-replay path to rebuild the cache.

## Alternatives Considered

### Unconditional SET and DEL

Rejected because lease expiry allows a stale owner to mutate a newer owner's state.

### Check with GET, then mutate in a second command

Rejected because another owner can acquire the key between the check and mutation.

### Fencing token enforced by PostgreSQL writes

Not required for this milestone because Redis is not authoritative for durable writes.
The database uniqueness constraint is the final write boundary. A monotonically
increasing fencing token would be appropriate if the leased resource itself accepted
writes outside PostgreSQL's protection.

## Consequences

- Redis completion and cleanup require Lua scripts.
- A failed compare is treated as lost ownership, not as permission to overwrite.
- Operational force-unlock must use the same compare-and-delete rule.
- Rolling deployment must drain old writers or wait at least one processing TTL before
  relying on owner-token enforcement across every instance.

## Evidence

- `RedisPaymentIntakeIntegrationTest.staleReservationCannotDeleteAReplacementOwner`
- `RedisPaymentIntakeIntegrationTest.staleReservationCannotCompleteOverAReplacementOwner`
