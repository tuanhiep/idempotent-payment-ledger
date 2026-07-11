package infra.systemdesign.paymentledger.application;

import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.application.port.LedgerStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.PaymentInProgressException;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;


@Service
public class PaymentIntakeService {

    private final IdempotencyStore idempotencyStore;
    private final LedgerStore ledgerStore;
    private final MeterRegistry meterRegistry;

    public PaymentIntakeService(
            IdempotencyStore idempotencyStore,
            LedgerStore ledgerStore,
            MeterRegistry meterRegistry) {
        this.idempotencyStore = idempotencyStore;
        this.ledgerStore = ledgerStore;
        this.meterRegistry = meterRegistry;
    }

    public PaymentResponse process(String idempotencyKey, PaymentRequest request) {
        String normalizedKey = normalizeKey(idempotencyKey);
        PaymentRequest canonicalRequest = request.canonical();
        String payloadHash = sha256(canonicalRequest.payloadFingerprint());

        try {
            IdempotencyStore.Reservation reservation = idempotencyStore.reserve(normalizedKey, payloadHash);
            if (reservation instanceof IdempotencyStore.ExistingReservation existing) {
                incrementMetric("replayed");
                return existing.record().replay();
            }

            IdempotencyStore.NewReservation newReservation = (IdempotencyStore.NewReservation) reservation;
            try {
                PaymentResponse response = ledgerStore.recordPaymentAndComplete(normalizedKey, canonicalRequest, newReservation);
                incrementMetric(response.replayed() ? "replayed" : "accepted");
                return response;
            } catch (DuplicateIdempotencyKeyException | PaymentInProgressException exception) {
                idempotencyStore.fail(newReservation, exception);
                throw exception;
            } catch (org.springframework.dao.DataIntegrityViolationException exception) {
                // Concurrency DB race lost — the other thread committed first.
                // Replay the winner's committed payment.
                try {
                    idempotencyStore.fail(newReservation, exception); // Release outer-bound lock
                    PaymentResponse replayResponse = ledgerStore.replayPayment(normalizedKey, canonicalRequest, newReservation);
                    incrementMetric("replayed");
                    return replayResponse;
                } catch (RuntimeException replayEx) {
                    idempotencyStore.fail(newReservation, replayEx);
                    incrementMetric("failed");
                    throw replayEx;
                }
            } catch (RuntimeException exception) {
                idempotencyStore.fail(newReservation, exception);
                incrementMetric("failed");
                throw exception;
            }
        } catch (DuplicateIdempotencyKeyException exception) {
            incrementMetric("conflict");
            throw exception;
        } catch (PaymentInProgressException exception) {
            incrementMetric("early");
            throw exception;
        }
    }

    private void incrementMetric(String status) {
        if (meterRegistry != null) {
            meterRegistry.counter("payment.intake.requests", "status", status).increment();
        }
    }


    private static String normalizeKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        return idempotencyKey.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
