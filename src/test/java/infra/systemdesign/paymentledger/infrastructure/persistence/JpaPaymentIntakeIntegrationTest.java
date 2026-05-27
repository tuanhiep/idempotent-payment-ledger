package infra.systemdesign.paymentledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import infra.systemdesign.paymentledger.application.PaymentIntakeService;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.InsufficientFundsException;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Integration tests for JPA-backed payment intake.
 *
 * <p>Activates the {@code jpa} profile which wires JpaIdempotencyStore and JpaLedgerStore
 * instead of the in-memory adapters. Flyway applies the same PostgreSQL migration that local
 * Docker and production-like environments use.
 */
@SpringBootTest
@ActiveProfiles("jpa")
@Sql(
        statements = {
            "TRUNCATE TABLE ledger_entries, ledger_transactions, payments, idempotency_records, outbox_events, accounts RESTART IDENTITY CASCADE",
            "INSERT INTO accounts (account_id, tenant_id, balance, currency, created_at, updated_at) VALUES ('acct-payer', 'default', 1000.00, 'USD', now(), now())",
            "INSERT INTO accounts (account_id, tenant_id, balance, currency, created_at, updated_at) VALUES ('acct-merchant', 'default', 0.00, 'USD', now(), now())"
        },
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD
)
class JpaPaymentIntakeIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired
    private PaymentIntakeService paymentIntakeService;

    @Autowired
    private JpaLedgerStore jpaLedgerStore;

    @Autowired
    private LedgerEntryJpaRepository ledgerEntryRepository;

    @Test
    void firstPaymentPersistsBalancedLedgerEntries() {
        PaymentResponse response = paymentIntakeService.process("jpa-001", request("100.00"));

        assertThat(response.replayed()).isFalse();
        assertThat(response.status()).isEqualTo("ACCEPTED");

        List<LedgerEntry> entries = jpaLedgerStore.entriesForTransaction(response.ledgerTransactionId());
        assertThat(entries).hasSize(2);
        assertThat(jpaLedgerStore.balanceForTransaction(response.ledgerTransactionId()))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void duplicateSamePayloadReturnsReplayWithoutNewEntries() {
        PaymentResponse first = paymentIntakeService.process("jpa-002", request("50.00"));
        PaymentResponse replay = paymentIntakeService.process("jpa-002", request("50.00"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.paymentId()).isEqualTo(first.paymentId());
        assertThat(replay.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());

        // Only 2 entries in DB — replay did not create new ledger entries
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void duplicateKeyWithDifferentPayloadIsRejected() {
        paymentIntakeService.process("jpa-003", request("10.00"));

        assertThatThrownBy(() -> paymentIntakeService.process("jpa-003", request("11.00")))
                .isInstanceOf(DuplicateIdempotencyKeyException.class)
                .hasMessageContaining("different payment payload");

        // Only 2 entries from the first successful request
        assertThat(ledgerEntryRepository.count()).isEqualTo(2);
    }

    @Test
    void invalidAmountIsRejectedBeforeLedgerMutation() {
        PaymentRequest invalid = new PaymentRequest(
                "acct-payer", "acct-merchant", new BigDecimal("-5.00"), "USD");

        assertThatThrownBy(() -> paymentIntakeService.process("jpa-004", invalid))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(ledgerEntryRepository.count()).isZero();
    }

    @Test
    void ledgerBalanceInvariantVerifiedByReconciliationQuery() {
        PaymentResponse response = paymentIntakeService.process("jpa-005", request("250.00"));

        // Reconciliation query: SUM(CREDIT) - SUM(DEBIT) must equal zero for any transaction.
        BigDecimal balance = jpaLedgerStore.balanceForTransaction(response.ledgerTransactionId());

        assertThat(balance)
                .as("ledger transaction must balance to zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void multiplePaymentsEachHaveIndependentBalancedEntries() {
        PaymentResponse first = paymentIntakeService.process("jpa-006a", request("100.00"));
        PaymentResponse second = paymentIntakeService.process("jpa-006b", request("200.00"));

        assertThat(jpaLedgerStore.balanceForTransaction(first.ledgerTransactionId()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(jpaLedgerStore.balanceForTransaction(second.ledgerTransactionId()))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ledgerEntryRepository.count()).isEqualTo(4);
    }

    @Test
    void insufficientFundsIsRejectedAndRollsBack() {
        // acct-payer has $1000.00 initially in DB. Try to pay $1001.00
        PaymentRequest huge = request("1001.00");

        assertThatThrownBy(() -> paymentIntakeService.process("jpa-huge", huge))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("insufficient funds");

        // DB state is clean — no entries recorded, balance remains unchanged
        assertThat(ledgerEntryRepository.count()).isZero();
        assertThat(jpaLedgerStore.getAccountBalance("acct-payer")).isEqualByComparingTo("1000.0000");
    }

    @Test
    void concurrentDoubleSpendingPreventedByDurableOverdraftProtection() throws Exception {
        // Giả lập 10 threads đồng thời thực hiện 10 payments khác nhau (10 keys khác nhau).
        // Mỗi request rút $200. Số dư ban đầu = $1000.
        // Kỳ vọng: Đúng 5 threads thành công (5 x 200 = 1000), 5 threads còn lại bị InsufficientFundsException.
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
                            paymentIntakeService.process("jpa-concurrent-spend-" + index, request("200.00"));
                            successCount.incrementAndGet();
                        } catch (InsufficientFundsException e) {
                            failureCount.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            // Wait for all virtual threads to complete
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }

            // Verify outcomes
            assertThat(successCount.get()).isEqualTo(5);
            assertThat(failureCount.get()).isEqualTo(5);

            // DB Verification: Payer balance is exactly 0.00, Merchant is exactly 1000.00
            assertThat(jpaLedgerStore.getAccountBalance("acct-payer")).isEqualByComparingTo("0.0000");
            assertThat(jpaLedgerStore.getAccountBalance("acct-merchant")).isEqualByComparingTo("1000.0000");

            // Total ledger entry count is exactly 10 (5 Debit entries + 5 Credit entries)
            assertThat(ledgerEntryRepository.count()).isEqualTo(10);
        }
    }

    private static PaymentRequest request(String amount) {
        return new PaymentRequest("acct-payer", "acct-merchant", new BigDecimal(amount), "USD");
    }
}
