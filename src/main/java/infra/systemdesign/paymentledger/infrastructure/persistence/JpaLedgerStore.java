package infra.systemdesign.paymentledger.infrastructure.persistence;

import infra.systemdesign.paymentledger.application.port.LedgerStore;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.LedgerEntryType;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerEntryEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerTransactionEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.PaymentEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerTransactionJpaRepository;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.PaymentJpaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable ledger store backed by a relational database.
 *
 * <p>{@code recordPayment()} participates in the outer {@code @Transactional} started by
 * {@code PaymentIntakeService.process()} (propagation = REQUIRED). This means the payment
 * row, ledger transaction, and both ledger entries commit atomically with the
 * idempotency ACCEPTED update — satisfying the double-entry balance invariant
 * even under crash scenarios.
 *
 * <p>Active only when the {@code jpa} Spring profile is set.
 */
@Repository
@Profile("jpa")
public class JpaLedgerStore implements LedgerStore {

    private static final String TENANT_ID = "default";
    private static final String POSTING_RULE = "PAYMENT_ACCEPTED";
    private static final int POSTING_RULE_VERSION = 1;

    private final PaymentJpaRepository paymentRepository;
    private final LedgerTransactionJpaRepository ledgerTransactionRepository;
    private final LedgerEntryJpaRepository ledgerEntryRepository;
    private final Clock clock;

    public JpaLedgerStore(
            PaymentJpaRepository paymentRepository,
            LedgerTransactionJpaRepository ledgerTransactionRepository,
            LedgerEntryJpaRepository ledgerEntryRepository,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.clock = clock;
    }

    /**
     * Records the payment, ledger transaction, and two balanced ledger entries.
     *
     * <p>All three INSERTs participate in the caller's transaction (REQUIRED).
     * Rollback of the outer transaction rolls back all three writes atomically.
     *
     * @return the ledger transaction ID
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerWriteResult recordPayment(String idempotencyKey, PaymentRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        String paymentId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        // Step 1: insert the payment record
        paymentRepository.save(new PaymentEntity(
                paymentId,
                TENANT_ID,
                idempotencyKey,
                request.payerAccountId(),
                request.merchantAccountId(),
                request.amount(),
                request.currency(),
                "ACCEPTED",
                now
        ));

        // Step 2: insert the ledger transaction (accounting event)
        ledgerTransactionRepository.save(new LedgerTransactionEntity(
                transactionId,
                paymentId,
                POSTING_RULE,
                POSTING_RULE_VERSION,
                "POSTED",
                now
        ));

        // Step 3: insert balanced debit + credit entries
        ledgerEntryRepository.save(new LedgerEntryEntity(
                UUID.randomUUID().toString(),
                transactionId,
                request.payerAccountId(),
                LedgerEntryType.DEBIT.name(),
                request.amount(),
                request.currency(),
                now
        ));
        ledgerEntryRepository.save(new LedgerEntryEntity(
                UUID.randomUUID().toString(),
                transactionId,
                request.merchantAccountId(),
                LedgerEntryType.CREDIT.name(),
                request.amount(),
                request.currency(),
                now
        ));

        return new LedgerWriteResult(paymentId, transactionId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public List<LedgerEntry> entriesForTransaction(String transactionId) {
        return ledgerEntryRepository.findByTransactionId(transactionId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public BigDecimal balanceForTransaction(String transactionId) {
        return ledgerEntryRepository.findByTransactionId(transactionId).stream()
                .map(entry -> "CREDIT".equals(entry.getEntryType())
                        ? entry.getAmount()
                        : entry.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public int entryCount() {
        return (int) ledgerEntryRepository.count();
    }

    private LedgerEntry toDomain(LedgerEntryEntity entity) {
        return new LedgerEntry(
                entity.getEntryId(),
                entity.getTransactionId(),
                entity.getAccountId(),
                LedgerEntryType.valueOf(entity.getEntryType()),
                entity.getAmount(),
                entity.getCurrency(),
                entity.getCreatedAt().toInstant()
        );
    }
}
