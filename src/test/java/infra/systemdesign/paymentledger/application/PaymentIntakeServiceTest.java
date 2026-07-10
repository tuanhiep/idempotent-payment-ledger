package infra.systemdesign.paymentledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import infra.systemdesign.paymentledger.infrastructure.idempotency.InMemoryIdempotencyStore;
import infra.systemdesign.paymentledger.infrastructure.ledger.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class PaymentIntakeServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-14T09:00:00Z"), ZoneOffset.UTC);
    private final IdempotencyStore idempotencyStore = new InMemoryIdempotencyStore();
    private final InMemoryLedgerStore ledgerStore = new InMemoryLedgerStore(idempotencyStore, clock);
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final PaymentIntakeService paymentIntakeService = new PaymentIntakeService(
            idempotencyStore, ledgerStore, clock, meterRegistry);


    @Test
    void firstPaymentCreatesBalancedLedgerEntries() {
        PaymentResponse response = paymentIntakeService.process("pay-001", request("100.00"));

        assertThat(response.replayed()).isFalse();
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(ledgerStore.entriesForTransaction(response.ledgerTransactionId())).hasSize(2);
        assertThat(ledgerStore.balanceForTransaction(response.ledgerTransactionId()))
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Verify accepted metric
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "accepted").count())
                .isEqualTo(1.0);
    }

    @Test
    void duplicateRequestWithSamePayloadReturnsStoredResponseWithoutNewLedgerEntries() {
        PaymentResponse first = paymentIntakeService.process("pay-002", request("42.50"));
        PaymentResponse replay = paymentIntakeService.process("pay-002", request("42.50"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
        assertThat(ledgerStore.entryCount()).isEqualTo(2);

        // Verify metrics
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "accepted").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "replayed").count())
                .isEqualTo(1.0);
    }

    @Test
    void concurrentDuplicateRequestsCreateOneLedgerTransaction() throws Exception {
        int requestCount = 20;
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, requestCount)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await(5, TimeUnit.SECONDS);
                        return paymentIntakeService.process("pay-concurrent", request("77.00"));
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<PaymentResponse> responses = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(5, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            String paymentId = responses.getFirst().paymentId();
            String ledgerTransactionId = responses.getFirst().ledgerTransactionId();

            assertThat(responses)
                    .extracting(PaymentResponse::paymentId)
                    .containsOnly(paymentId);
            assertThat(responses)
                    .extracting(PaymentResponse::ledgerTransactionId)
                    .containsOnly(ledgerTransactionId);
            assertThat(responses)
                    .filteredOn(PaymentResponse::replayed)
                    .hasSize(requestCount - 1);
            assertThat(ledgerStore.entryCount()).isEqualTo(2);
        }
    }

    @Test
    void duplicateKeyWithDifferentPayloadIsRejected() {
        paymentIntakeService.process("pay-003", request("10.00"));

        assertThatThrownBy(() -> paymentIntakeService.process("pay-003", request("11.00")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("different payment payload");
        assertThat(ledgerStore.entryCount()).isEqualTo(2);

        // Verify metrics
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "accepted").count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "conflict").count())
                .isEqualTo(1.0);
    }

    @Test
    void invalidAmountIsRejectedBeforeLedgerMutation() {
        PaymentRequest invalid = new PaymentRequest(
                "acct-payer",
                "acct-merchant",
                new BigDecimal("-1.00"),
                "USD"
        );

        assertThatThrownBy(() -> paymentIntakeService.process("pay-004", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
        assertThat(ledgerStore.entryCount()).isZero();
    }

    @Test
    void sameAccountAfterTrimmingIsRejectedBeforeLedgerMutation() {
        PaymentRequest invalid = new PaymentRequest(
                "acct-payer ",
                "acct-payer",
                new BigDecimal("1.00"),
                "USD"
        );

        assertThatThrownBy(() -> paymentIntakeService.process("pay-005", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be different");
        assertThat(ledgerStore.entryCount()).isZero();
    }

    @Test
    void amountWithMoreThanTwoDecimalPlacesIsRejectedBeforeLedgerMutation() {
        PaymentRequest invalid = new PaymentRequest(
                "acct-payer",
                "acct-merchant",
                new BigDecimal("1.001"),
                "USD"
        );

        assertThatThrownBy(() -> paymentIntakeService.process("pay-006", invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most two decimal places");
        assertThat(ledgerStore.entryCount()).isZero();
    }

    @Test
    void insufficientFundsPayerIsRejectedBeforeLedgerMutation() {
        PaymentRequest hugeRequest = request("1001.00");

        assertThatThrownBy(() -> paymentIntakeService.process("pay-huge", hugeRequest))
                .isInstanceOf(infra.systemdesign.paymentledger.domain.InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");
        assertThat(ledgerStore.entryCount()).isZero();

        // Verify metrics
        assertThat(meterRegistry.counter("payment.intake.requests", "status", "failed").count())
                .isEqualTo(1.0);
    }

    @Test
    void paymentInProgressThrowsPaymentInProgressExceptionAndIncrementsEarlyMetric() {
        IdempotencyStore mockStore = new IdempotencyStore() {
            @Override
            public Reservation reserve(String key, String payloadHash) {
                throw new infra.systemdesign.paymentledger.domain.PaymentInProgressException("in progress");
            }
            @Override
            public void complete(NewReservation reservation, PaymentResponse response) {}
            @Override
            public void fail(NewReservation reservation, RuntimeException failure) {}
        };
        MeterRegistry localRegistry = new SimpleMeterRegistry();
        PaymentIntakeService serviceWithMock = new PaymentIntakeService(
                mockStore, ledgerStore, clock, localRegistry);

        assertThatThrownBy(() -> serviceWithMock.process("pay-inprogress", request("50.00")))
                .isInstanceOf(infra.systemdesign.paymentledger.domain.PaymentInProgressException.class);

        assertThat(localRegistry.counter("payment.intake.requests", "status", "early").count())
                .isEqualTo(1.0);
    }

    private static PaymentRequest request(String amount) {
        return new PaymentRequest(
                "acct-payer",
                "acct-merchant",
                new BigDecimal(amount),
                "usd"
        );
    }
}
