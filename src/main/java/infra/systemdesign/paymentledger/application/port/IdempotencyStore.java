package infra.systemdesign.paymentledger.application.port;

import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentResponse;

public interface IdempotencyStore {

    Reservation reserve(String key, String payloadHash);

    void complete(NewReservation reservation, PaymentResponse response);

    void fail(NewReservation reservation, RuntimeException failure);

    sealed interface Reservation permits NewReservation, ExistingReservation {}

    record NewReservation(String key, String payloadHash) implements Reservation {}

    record ExistingReservation(IdempotencyRecord record) implements Reservation {}
}
