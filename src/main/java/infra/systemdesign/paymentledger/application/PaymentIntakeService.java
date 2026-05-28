package infra.systemdesign.paymentledger.application;

import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.application.port.LedgerStore;
import infra.systemdesign.paymentledger.application.port.LedgerStore.LedgerWriteResult;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import org.springframework.stereotype.Service;


@Service
public class PaymentIntakeService {

    private final IdempotencyStore idempotencyStore;
    private final LedgerStore ledgerStore;
    private final Clock clock;

    public PaymentIntakeService(IdempotencyStore idempotencyStore, LedgerStore ledgerStore, Clock clock) {
        this.idempotencyStore = idempotencyStore;
        this.ledgerStore = ledgerStore;
        this.clock = clock;
    }

    public PaymentResponse process(String idempotencyKey, PaymentRequest request) {
        String normalizedKey = normalizeKey(idempotencyKey);
        PaymentRequest canonicalRequest = request.canonical();
        String payloadHash = sha256(canonicalRequest.payloadFingerprint());

        IdempotencyStore.Reservation reservation = idempotencyStore.reserve(normalizedKey, payloadHash);
        if (reservation instanceof IdempotencyStore.ExistingReservation existing) {
            return existing.record().replay();
        }

        IdempotencyStore.NewReservation newReservation = (IdempotencyStore.NewReservation) reservation;
        try {
            return ledgerStore.recordPaymentAndComplete(normalizedKey, canonicalRequest, newReservation);
        } catch (RuntimeException exception) {
            idempotencyStore.fail(newReservation, exception);
            throw exception;
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
