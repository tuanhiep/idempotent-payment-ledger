package infra.systemdesign.paymentledger.domain;

/**
 * Thrown when a duplicate request arrives while the original request is still being
 * processed (idempotency record status = PROCESSING).
 *
 * <p>Maps to HTTP 425 Too Early — the client should retry after a short delay.
 * This is a transient state in production: once the original request completes,
 * subsequent retries with the same key and payload will receive a 200 replay.
 *
 * <p>Production gap: a stuck PROCESSING record (e.g. from a crashed API instance)
 * will keep returning 425 until a cleanup job or timeout clears the record.
 * See ADR-001 "Must revisit" section for the planned resolution strategy.
 */
public class PaymentInProgressException extends RuntimeException {

    public PaymentInProgressException(String message) {
        super(message);
    }
}
