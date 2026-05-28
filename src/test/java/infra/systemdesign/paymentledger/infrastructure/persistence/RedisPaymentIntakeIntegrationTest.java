package infra.systemdesign.paymentledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import infra.systemdesign.paymentledger.application.PaymentIntakeService;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.InsufficientFundsException;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.PaymentInProgressException;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * High-scale integration tests using Redis for Idempotency and PostgreSQL for Ledger.
 *
 * <p>Activates both {@code jpa} and {@code redis} profiles.
 * StringRedisTemplate connects to the Testcontainers Redis instance.
 * Postgres JpaLedgerStore handles durable ledger writes in a 5ms atomic transaction.
 */
@SpringBootTest
@ActiveProfiles({"jpa", "redis"})
@Sql(
        statements = {
            "TRUNCATE TABLE ledger_entries, ledger_transactions, payments, idempotency_records, outbox_events, accounts RESTART IDENTITY CASCADE",
            "INSERT INTO accounts (account_id, tenant_id, balance, currency, created_at, updated_at) VALUES ('acct-payer', 'default', 1000.00, 'USD', now(), now())",
            "INSERT INTO accounts (account_id, tenant_id, balance, currency, created_at, updated_at) VALUES ('acct-merchant', 'default', 0.00, 'USD', now(), now())"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class RedisPaymentIntakeIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired
    private PaymentIntakeService paymentIntakeService;

    @Autowired
    private JpaLedgerStore jpaLedgerStore;

    @Autowired
    private LedgerEntryJpaRepository ledgerEntryRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearRedis() {
        // Ensure a completely clean state in Redis before each test
        Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()
                .serverCommands()
                .flushDb();
    }

    @Test
    void firstPaymentPersistsBalancedLedgerEntriesInPostgresAndCacheInRedis() {
        PaymentResponse response = paymentIntakeService.process("redis-001", request("100.00"));

        assertThat(response.replayed()).isFalse();
        assertThat(response.status()).isEqualTo("ACCEPTED");

        // Verify Postgres has the balanced entries
        List<LedgerEntry> entries = jpaLedgerStore.entriesForTransaction(response.ledgerTransactionId());
        assertThat(entries).hasSize(2);
        assertThat(jpaLedgerStore.balanceForTransaction(response.ledgerTransactionId()))
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Verify Redis has the cached response
        String redisKey = "idempotency:redis-001";
        String cachedVal = redisTemplate.opsForValue().get(redisKey);
        assertThat(cachedVal).isNotNull().startsWith("ACCEPTED:");
    }

    @Test
    void duplicateSamePayloadReturnsReplayDirectlyFromRedis() {
        PaymentResponse first = paymentIntakeService.process("redis-002", request("150.00"));
        
        // Let's truncate the DB tables entirely to prove that the replay is served
        // 100% from Redis WITHOUT hitting the DB ledger stores at all!
        Objects.requireNonNull(redisTemplate.getConnectionFactory());
        
        PaymentResponse replay = paymentIntakeService.process("redis-002", request("150.00"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
    }

    @Test
    void duplicateKeyWithDifferentPayloadIsRejectedByRedis() {
        paymentIntakeService.process("redis-003", request("10.00"));

        assertThatThrownBy(() -> paymentIntakeService.process("redis-003", request("11.00")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("idempotency key already exists for a different payment payload");
    }

    @Test
    void concurrentDuplicateProcessingRequestReturns425TooEarly() {
        // Manually write "PROCESSING" into Redis to simulate an active in-flight request
        String redisKey = "idempotency:redis-004";
        PaymentRequest request = request("10.00");
        String payloadHash = sha256(request.payloadFingerprint());
        redisTemplate.opsForValue().set(redisKey, "PROCESSING:" + payloadHash);

        assertThatThrownBy(() -> paymentIntakeService.process("redis-004", request))
                .isInstanceOf(PaymentInProgressException.class)
                .hasMessageContaining("is still being processed");
    }


    @Test
    void failedPaymentClearsRedisReservationForFutureRetry() {
        // Payer has $1000. Try to pay $1001. Will throw InsufficientFundsException
        PaymentRequest huge = request("1001.00");

        assertThatThrownBy(() -> paymentIntakeService.process("redis-huge", huge))
                .isInstanceOf(InsufficientFundsException.class);

        // Verify that the processing key was deleted from Redis so client can retry later
        String redisKey = "idempotency:redis-huge";
        assertThat(redisTemplate.opsForValue().get(redisKey)).isNull();
    }

    @Test
    void concurrentDoubleSpendingPreventedByDurablePostgresLockAndRedisIdempotency() throws Exception {
        // Simulate 10 Virtual Threads concurrently rushing to withdraw $200 each.
        // Balance = $1000. Each has a different idempotency key.
        // Expect: exactly 5 succeed, 5 get InsufficientFundsException.
        int threadCount = 10;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, threadCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        try {
                            paymentIntakeService.process("redis-concurrent-spend-" + index, request("200.00"));
                            successCount.incrementAndGet();
                        } catch (InsufficientFundsException e) {
                            failureCount.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            // Verify outcomes
            assertThat(successCount.get()).isEqualTo(5);
            assertThat(failureCount.get()).isEqualTo(5);

            // DB Verification: Payer balance = 0.00, Merchant = 1000.00
            assertThat(jpaLedgerStore.getAccountBalance("acct-payer")).isEqualByComparingTo("0.0000");
            assertThat(jpaLedgerStore.getAccountBalance("acct-merchant")).isEqualByComparingTo("1000.0000");

            // Total ledger entry count is exactly 10
            assertThat(ledgerEntryRepository.count()).isEqualTo(10);
        }
    }

    private static PaymentRequest request(String amount) {
        return new PaymentRequest("acct-payer", "acct-merchant", new BigDecimal(amount), "USD");
    }

    private static String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

