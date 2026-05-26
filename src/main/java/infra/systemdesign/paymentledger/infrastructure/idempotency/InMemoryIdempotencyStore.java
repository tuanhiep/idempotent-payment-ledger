package infra.systemdesign.paymentledger.infrastructure.idempotency;

import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!jpa")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, InFlightRecord> records = new ConcurrentHashMap<>();

    @Override
    public Reservation reserve(String key, String payloadHash) {
        InFlightRecord candidate = new InFlightRecord(key, payloadHash);
        InFlightRecord existing = records.putIfAbsent(key, candidate);
        if (existing == null) {
            return new NewReservation(key, payloadHash);
        }
        return new ExistingReservation(existing.awaitMatching(payloadHash));
    }

    @Override
    public void complete(NewReservation reservation, PaymentResponse response) {
        InFlightRecord existing = records.get(reservation.key());
        if (existing == null) {
            throw new IllegalStateException("missing idempotency reservation for key " + reservation.key());
        }
        existing.complete(new IdempotencyRecord(reservation.key(), reservation.payloadHash(), response));
    }

    @Override
    public void fail(NewReservation reservation, RuntimeException failure) {
        InFlightRecord existing = records.get(reservation.key());
        if (existing != null) {
            existing.fail(failure);
            records.remove(reservation.key(), existing);
        }
    }

    private static final class InFlightRecord {

        private final String key;
        private final String payloadHash;
        private final Lock lock = new ReentrantLock();
        private final Condition completed = lock.newCondition();
        private IdempotencyRecord completedRecord;
        private RuntimeException failure;
        private boolean done;

        private InFlightRecord(String key, String payloadHash) {
            this.key = key;
            this.payloadHash = payloadHash;
        }

        private IdempotencyRecord awaitMatching(String incomingPayloadHash) {
            if (!payloadHash.equals(incomingPayloadHash)) {
                throw new DuplicateIdempotencyKeyException(
                        "idempotency key already exists for a different payment payload"
                );
            }

            lock.lock();
            try {
                while (!done) {
                    completed.await();
                }
                if (failure != null) {
                    throw failure;
                }
                return completedRecord;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for idempotency outcome", exception);
            } finally {
                lock.unlock();
            }
        }

        private void complete(IdempotencyRecord record) {
            lock.lock();
            try {
                completedRecord = record;
                done = true;
                completed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void fail(RuntimeException exception) {
            lock.lock();
            try {
                failure = exception;
                done = true;
                completed.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }
}
