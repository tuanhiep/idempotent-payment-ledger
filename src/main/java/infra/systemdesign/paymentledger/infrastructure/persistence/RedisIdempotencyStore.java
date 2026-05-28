package infra.systemdesign.paymentledger.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentInProgressException;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Distributed high-performance idempotency store backed by Redis.
 *
 * <p>Uses Redis atomic 'SETNX' (setIfAbsent) as a distributed lock/reservation layer
 * outside of the database transaction, completely eliminating DB connection starvation
 * and lock contention.
 *
 * <p>Active only under the {@code redis} Spring profile.
 */
@Repository
@Profile("redis")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final String KEY_PREFIX = "idempotency:";
    private static final long LOCK_TTL_SECONDS = 120; // 2-minute processing lock
    private static final long COMPLETED_TTL_DAYS = 7;  // 7-day retention for success response

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Reservation reserve(String key, String payloadHash) {
        String redisKey = KEY_PREFIX + key;
        String processingVal = "PROCESSING:" + payloadHash;

        // Atomic Set-NX (Only if Not Exists) with TTL for processing lock
        Boolean success = redisTemplate.opsForValue().setIfAbsent(
                redisKey,
                processingVal,
                Duration.ofSeconds(LOCK_TTL_SECONDS)
        );

        if (Boolean.TRUE.equals(success)) {
            return new NewReservation(key, payloadHash);
        }

        // Key already exists — retrieve value and resolve state
        String val = redisTemplate.opsForValue().get(redisKey);
        if (val == null) {
            // Race condition: key expired or was deleted just between setIfAbsent and get.
            // Retry reservation once recursively.
            return reserve(key, payloadHash);
        }

        return resolveExisting(key, val, payloadHash);
    }

    @Override
    public void complete(NewReservation reservation, PaymentResponse response) {
        String redisKey = KEY_PREFIX + reservation.key();
        String completedVal = "ACCEPTED:" + reservation.payloadHash() + ":" + toJson(response);

        // Store completed response with 7-day retention TTL
        redisTemplate.opsForValue().set(
                redisKey,
                completedVal,
                Duration.ofDays(COMPLETED_TTL_DAYS)
        );
    }

    @Override
    public void fail(NewReservation reservation, RuntimeException failure) {
        String redisKey = KEY_PREFIX + reservation.key();
        // Remove processing key so that subsequent retry requests can start fresh
        redisTemplate.delete(redisKey);
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    private Reservation resolveExisting(String key, String val, String incomingPayloadHash) {
        if (val.startsWith("PROCESSING:")) {
            String storedHash = val.substring("PROCESSING:".length());
            if (!storedHash.equals(incomingPayloadHash)) {
                throw new DuplicateIdempotencyKeyException(
                        "idempotency key already exists for a different payment payload");
            }
            throw new PaymentInProgressException(
                    "payment with key " + key + " is still being processed");
        }

        if (val.startsWith("ACCEPTED:")) {
            int secondColonIndex = val.indexOf(':', "ACCEPTED:".length());
            if (secondColonIndex == -1) {
                throw new IllegalStateException("malformed completed idempotency value in Redis: " + val);
            }
            String storedHash = val.substring("ACCEPTED:".length(), secondColonIndex);
            if (!storedHash.equals(incomingPayloadHash)) {
                throw new DuplicateIdempotencyKeyException(
                        "idempotency key already exists for a different payment payload");
            }
            String json = val.substring(secondColonIndex + 1);
            PaymentResponse response = fromJson(json, PaymentResponse.class);
            return new ExistingReservation(new IdempotencyRecord(key, storedHash, response));
        }

        throw new IllegalStateException("unexpected idempotency record value in Redis: " + val);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize response to JSON", e);
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize response from JSON", e);
        }
    }
}
