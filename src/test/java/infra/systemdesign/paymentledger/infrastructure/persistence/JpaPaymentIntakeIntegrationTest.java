package infra.systemdesign.paymentledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import infra.systemdesign.paymentledger.application.PaymentIntakeService;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Integration tests for JPA-backed payment intake.
 *
 * <p>Activates the {@code jpa} profile which wires JpaIdempotencyStore and JpaLedgerStore
 * instead of the in-memory adapters. Flyway applies the same PostgreSQL migration that local
 * Docker and production-like environments use.
 *
 * <p>Tests prove:
 * <ul>
 *   <li>First payment creates balanced ledger entries in the database.</li>
 *   <li>Duplicate same-payload request replays from the database without new entries.</li>
 *   <li>Duplicate key with different payload is rejected (409 — DB constraint not reached
 *       because payload hash check fires first).</li>
 *   <li>Invalid request is rejected before any ledger mutation.</li>
 *   <li>Ledger entries for one transaction balance to zero (reconciliation invariant).</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("jpa")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@Sql(
        statements = "TRUNCATE TABLE ledger_entries, ledger_transactions, payments, "
                + "idempotency_records, outbox_events RESTART IDENTITY CASCADE",
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
        // This is the core double-entry ledger invariant verified via the actual DB.
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

    private static PaymentRequest request(String amount) {
        return new PaymentRequest("acct-payer", "acct-merchant", new BigDecimal(amount), "USD");
    }
}
