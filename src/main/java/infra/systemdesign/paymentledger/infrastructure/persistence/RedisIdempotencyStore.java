package infra.systemdesign.paymentledger.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.application.port.ReservationOwnershipLostException;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.IdempotencyRecord;
import infra.systemdesign.paymentledger.domain.PaymentInProgressException;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Distributed high-performance idempotency store backed by Redis.
 *
 * <p>Uses Redis atomic 'SETNX' (setIfAbsent) as a distributed lock/reservation layer
 * outside of the database transaction, reducing duplicate pressure before requests reach
 * the authoritative PostgreSQL boundary.
 *
 * <p>Active only under the {@code redis} Spring profile.
 */
@Repository
@Profile("redis")
public class RedisIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);
    private static final String KEY_PREFIX = "idempotency:";
    private static final long LOCK_TTL_SECONDS = 120; // 2-minute processing lock
    private static final long COMPLETED_TTL_DAYS = 7;  // 7-day retention for success response
    private static final DefaultRedisScript<Long> COMPLETE_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3]); return 1 "
                    + "else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> DELETE_IF_OWNER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Reservation reserve(String key, String payloadHash) {
        String redisKey = KEY_PREFIX + key;
        String ownerToken = UUID.randomUUID().toString();
        String processingVal = processingValue(payloadHash, ownerToken);

        for (int attempt = 0; attempt < 3; attempt++) {
            // Atomic Set-NX (Only if Not Exists) with TTL for processing lock
            Boolean success = redisTemplate.opsForValue().setIfAbsent(
                    redisKey,
                    processingVal,
                    Duration.ofSeconds(LOCK_TTL_SECONDS)
            );

            if (Boolean.TRUE.equals(success)) {
                return new NewReservation(key, payloadHash, ownerToken);
            }

            // Key already exists — retrieve value and resolve state
            String val = redisTemplate.opsForValue().get(redisKey);
            if (val != null) {
                return resolveExisting(key, val, payloadHash);
            }
            // If val is null, it means a race condition occurred: key expired or was deleted just between setIfAbsent and get.
            // Loop and retry.
        }

        throw new IllegalStateException("Failed to reserve idempotency key " + key + " due to extreme concurrency lock racing.");
    }

    @Override
    public void complete(NewReservation reservation, PaymentResponse response) {
        String redisKey = KEY_PREFIX + reservation.key();
        String expectedProcessingVal = processingValue(reservation.payloadHash(), reservation.ownerToken());
        String completedVal = "ACCEPTED:" + reservation.payloadHash() + ":" + toJson(response);

        Long updated = redisTemplate.execute(
                COMPLETE_IF_OWNER_SCRIPT,
                List.of(redisKey),
                expectedProcessingVal,
                completedVal,
                Long.toString(Duration.ofDays(COMPLETED_TTL_DAYS).toMillis()));
        if (updated == null || updated == 0L) {
            throw new ReservationOwnershipLostException(
                    "idempotency reservation ownership was lost before completion for key "
                            + reservation.key());
        }
    }

    @Override
    public void fail(NewReservation reservation, RuntimeException failure) {
        String redisKey = KEY_PREFIX + reservation.key();
        String expectedProcessingVal = processingValue(reservation.payloadHash(), reservation.ownerToken());
        Long deleted = redisTemplate.execute(
                DELETE_IF_OWNER_SCRIPT,
                List.of(redisKey),
                expectedProcessingVal);
        if (deleted == null || deleted == 0L) {
            log.debug("Skipped cleanup for idempotency reservation no longer owned by caller: key={}",
                    reservation.key());
        }
    }

    // -------------------------------------------------------------------------
    // Private Helpers
    // -------------------------------------------------------------------------

    private Reservation resolveExisting(String key, String val, String incomingPayloadHash) {
        if (val.startsWith("PROCESSING:")) {
            int ownerSeparator = val.indexOf(':', "PROCESSING:".length());
            String storedHash = ownerSeparator == -1
                    ? val.substring("PROCESSING:".length())
                    : val.substring("PROCESSING:".length(), ownerSeparator);
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

    private static String processingValue(String payloadHash, String ownerToken) {
        return "PROCESSING:" + payloadHash + ":" + ownerToken;
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
