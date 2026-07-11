package infra.systemdesign.paymentledger.application.port;

import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentResponse;

public interface IdempotencyStore {

    Reservation reserve(String key, String payloadHash);

    void complete(NewReservation reservation, PaymentResponse response);

    void fail(NewReservation reservation, RuntimeException failure);

    /**
     * Indicates if completing a reservation requires deferring until the active
     * database transaction is committed (e.g. for non-durable external cache stores like Redis).
     *
     * @return true if completion should run after database commit, false if it should run
     * inside the database transaction.
     */
    default boolean requiresAfterCommitCompletion() {
        return true;
    }

    sealed interface Reservation permits NewReservation, ExistingReservation {}

    record NewReservation(String key, String payloadHash, String ownerToken) implements Reservation {

        public NewReservation {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("reservation key must not be blank");
            }
            if (payloadHash == null || payloadHash.isBlank()) {
                throw new IllegalArgumentException("reservation payload hash must not be blank");
            }
            if (ownerToken == null || ownerToken.isBlank()) {
                throw new IllegalArgumentException("reservation owner token must not be blank");
            }
        }
    }

    record ExistingReservation(IdempotencyRecord record) implements Reservation {}
}
