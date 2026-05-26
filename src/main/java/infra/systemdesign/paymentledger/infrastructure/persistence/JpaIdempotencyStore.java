package infra.systemdesign.paymentledger.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentInProgressException;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.IdempotencyRecordEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.IdempotencyRecordJpaRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Durable idempotency store backed by a relational database.
 *
 * <p>Race condition strategy: The UNIQUE constraint on (tenant_id, idempotency_key)
 * is the primary concurrency control — not application-level locks.
 *
 * <pre>
 * Thread A ──► INSERT idempotency_records (PROCESSING) ──► wins, continues
 * Thread B ──► INSERT idempotency_records (PROCESSING) ──► unique violation
 *                                                           └─► SELECT existing
 *                                                               ├─► ACCEPTED   → replay
 *                                                               ├─► PROCESSING → 425
 *                                                               └─► hash diff  → 409
 * </pre>
 *
 * <p>Why {@code TransactionTemplate} instead of {@code @Transactional(REQUIRES_NEW)}?
 *
 * <p>When a constraint violation occurs inside a Spring {@code @Transactional} method,
 * the Hibernate session enters an error state. Any subsequent JPA call on the same session
 * throws "transaction is marked for rollback". By isolating the INSERT inside a
 * {@code TransactionTemplate}, the failed INSERT rolls back in its own connection;
 * the fallback SELECT runs in a fresh context (the outer transaction from
 * {@code PaymentIntakeService.process()}).
 *
 * <p>Active only when the {@code jpa} Spring profile is set.
 */
@Repository
@Profile("jpa")
public class JpaIdempotencyStore implements IdempotencyStore {

    // Single-tenant placeholder for this slice.
    // Multi-tenancy: derive tenant_id from the security context.
    private static final String TENANT_ID = "default";

    // Idempotency records expire after 7 days — standard for payment systems.
    private static final int TTL_DAYS = 7;

    private final IdempotencyRecordJpaRepository repository;
    private final ObjectMapper objectMapper;

    // Runs the INSERT in a completely isolated transaction.
    // If INSERT fails (unique constraint), only this inner TX rolls back —
    // the caller's outer transaction from process() is unaffected and continues cleanly.
    private final TransactionTemplate requiresNewTemplate;

    public JpaIdempotencyStore(
            IdempotencyRecordJpaRepository repository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = template;
    }

    /**
     * Claims the idempotency key.
     *
     * <p>Step 1: Fast-path SELECT in the outer transaction from {@code process()}.
     * <p>Step 2: If not found, INSERT PROCESSING in an isolated {@code REQUIRES_NEW}
     * transaction via {@link TransactionTemplate}. The inner TX commits immediately,
     * making the key visible to concurrent requests before the outer TX commits.
     * <p>Step 3: If INSERT fails (unique constraint), the inner TX rolls back cleanly.
     * A second SELECT reads what the winning thread committed.
     */
    @Override
    public Reservation reserve(String key, String payloadHash) {
        // Fast path: key already exists (common for retries after ACCEPTED)
        return repository.findByTenantIdAndIdempotencyKey(TENANT_ID, key)
                .map(existing -> resolveExisting(existing, payloadHash))
                .orElseGet(() -> tryInsert(key, payloadHash));
    }

    /**
     * Marks the idempotency record ACCEPTED and stores the response.
     *
     * <p>Runs in the outer {@code @Transactional} from {@code PaymentIntakeService.process()}.
     * The ACCEPTED update and the ledger writes commit atomically — satisfying the invariant:
     * "if the ledger mutation commits, the idempotency outcome must also commit."
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void complete(NewReservation reservation, PaymentResponse response) {
        IdempotencyRecordEntity entity = repository
                .findByTenantIdAndIdempotencyKey(TENANT_ID, reservation.key())
                .orElseThrow(() -> new IllegalStateException(
                        "missing idempotency record for key: " + reservation.key()));
        entity.setStatus("ACCEPTED");
        entity.setResponseBody(toJson(response));
        entity.setCompletedAt(OffsetDateTime.now(ZoneOffset.UTC));
        // No explicit save() — entity is managed within the transaction; Hibernate
        // detects the dirty state and generates UPDATE on commit.
    }

    /**
     * Removes the PROCESSING record so the next retry gets a clean slate.
     *
     * <p>Uses a {@code REQUIRES_NEW} template so the DELETE commits independently
     * even when the outer transaction is rolling back. Without this, the DELETE
     * would roll back with the outer TX, leaving a permanent PROCESSING record
     * that blocks all retries with 425 Too Early.
     */
    @Override
    public void fail(NewReservation reservation, RuntimeException failure) {
        requiresNewTemplate.executeWithoutResult(status ->
                repository.deleteByTenantIdAndIdempotencyKey(TENANT_ID, reservation.key()));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Reservation tryInsert(String key, String payloadHash) {
        try {
            // INSERT commits immediately in its own transaction.
            // Any concurrent thread that also attempts INSERT will get a constraint
            // violation, roll back its own inner TX, and fall through to the fallback.
            requiresNewTemplate.executeWithoutResult(status -> {
                OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
                IdempotencyRecordEntity entity = new IdempotencyRecordEntity(
                        TENANT_ID,
                        key,
                        payloadHash,
                        payloadHash,  // request_body placeholder — full payload in next slice
                        "PROCESSING",
                        now,
                        now.plusDays(TTL_DAYS)
                );
                repository.saveAndFlush(entity);
            });
            return new NewReservation(key, payloadHash);

        } catch (DataIntegrityViolationException raceLost) {
            // The inner TX rolled back cleanly. The outer TX (from process()) is still
            // live and unaffected. Read what the winning thread committed.
            return repository.findByTenantIdAndIdempotencyKey(TENANT_ID, key)
                    .map(existing -> resolveExisting(existing, payloadHash))
                    .orElseThrow(() -> new IllegalStateException(
                            "expected idempotency record not found after constraint violation"
                                    + " for key: " + key));
        }
    }

    private Reservation resolveExisting(IdempotencyRecordEntity entity, String incomingPayloadHash) {
        if (!entity.getPayloadHash().equals(incomingPayloadHash)) {
            throw new DuplicateIdempotencyKeyException(
                    "idempotency key already exists for a different payment payload");
        }
        return switch (entity.getStatus()) {
            case "ACCEPTED" -> new ExistingReservation(toDomain(entity));
            case "PROCESSING" -> throw new PaymentInProgressException(
                    "payment with key " + entity.getIdempotencyKey() + " is still being processed");
            default -> throw new IllegalStateException(
                    "unexpected idempotency record status: " + entity.getStatus());
        };
    }

    private IdempotencyRecord toDomain(IdempotencyRecordEntity entity) {
        PaymentResponse response = fromJson(entity.getResponseBody(), PaymentResponse.class);
        return new IdempotencyRecord(entity.getIdempotencyKey(), entity.getPayloadHash(), response);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize from JSON", e);
        }
    }
}
