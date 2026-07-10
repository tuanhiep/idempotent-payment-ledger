package infra.systemdesign.paymentledger.infrastructure.persistence;

import infra.systemdesign.paymentledger.application.port.LedgerStore;
import infra.systemdesign.paymentledger.domain.DuplicateIdempotencyKeyException;
import infra.systemdesign.paymentledger.domain.InsufficientFundsException;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.LedgerEntryType;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.AccountEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerEntryEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerTransactionEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.entity.PaymentEntity;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.AccountJpaRepository;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerEntryJpaRepository;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.LedgerTransactionJpaRepository;
import infra.systemdesign.paymentledger.infrastructure.persistence.repository.PaymentJpaRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import infra.systemdesign.paymentledger.application.port.IdempotencyStore;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Durable ledger store backed by a relational database.
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
    private final AccountJpaRepository accountRepository;
    private final IdempotencyStore idempotencyStore;
    private final Clock clock;

    public JpaLedgerStore(
            PaymentJpaRepository paymentRepository,
            LedgerTransactionJpaRepository ledgerTransactionRepository,
            LedgerEntryJpaRepository ledgerEntryRepository,
            AccountJpaRepository accountRepository,
            IdempotencyStore idempotencyStore,
            Clock clock) {
        this.paymentRepository = paymentRepository;
        this.ledgerTransactionRepository = ledgerTransactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountRepository = accountRepository;
        this.idempotencyStore = idempotencyStore;
        this.clock = clock;
    }


    /**
     * Records the payment, ledger transaction, and two balanced ledger entries.
     *
     * <p>All operations participate in the caller's transaction (REQUIRED).
     *
     * @return the ledger transaction ID
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public LedgerWriteResult recordPayment(String idempotencyKey, PaymentRequest request) {
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        // Consistent Locking Order to prevent deadlock between concurrent cross-transfers
        String payerId = request.payerAccountId();
        String merchantId = request.merchantAccountId();
        String firstId = payerId.compareTo(merchantId) < 0 ? payerId : merchantId;
        String secondId = payerId.compareTo(merchantId) < 0 ? merchantId : payerId;

        // Step 0: Lock accounts in a strictly defined order using SELECT FOR UPDATE
        AccountEntity firstAccount = accountRepository.findByTenantIdAndAccountId(TENANT_ID, firstId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + firstId));
        AccountEntity secondAccount = accountRepository.findByTenantIdAndAccountId(TENANT_ID, secondId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + secondId));

        AccountEntity payer = payerId.equals(firstId) ? firstAccount : secondAccount;
        AccountEntity merchant = merchantId.equals(firstId) ? firstAccount : secondAccount;

        // Step 1: Validate balance (Overdraft protection / Double-spending prevention)
        if (payer.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    "insufficient funds in payer account " + payerId + " (balance: " + payer.getBalance() + ")");
        }

        // Step 2: Deduct from payer, add to merchant
        payer.setBalance(payer.getBalance().subtract(request.amount()));
        payer.setUpdatedAt(now);
        merchant.setBalance(merchant.getBalance().add(request.amount()));
        merchant.setUpdatedAt(now);

        String paymentId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();

        // Step 3: insert the payment record
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

        // Step 4: insert the ledger transaction (accounting event)
        ledgerTransactionRepository.save(new LedgerTransactionEntity(
                transactionId,
                paymentId,
                POSTING_RULE,
                POSTING_RULE_VERSION,
                "POSTED",
                now
        ));

        // Step 5: insert balanced debit + credit entries
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

    @Override
    @Transactional(propagation = Propagation.REQUIRED, readOnly = true)
    public BigDecimal getAccountBalance(String accountId) {
        return accountRepository.findReadOnlyByTenantIdAndAccountId(TENANT_ID, accountId)
                .map(AccountEntity::getBalance)
                .orElse(BigDecimal.ZERO);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public PaymentResponse recordPaymentAndComplete(
            String idempotencyKey,
            PaymentRequest request,
            IdempotencyStore.NewReservation reservation) {
        
        java.util.Optional<PaymentEntity> existingOpt = paymentRepository.findByTenantIdAndIdempotencyKey(TENANT_ID, idempotencyKey);
        if (existingOpt.isPresent()) {
            PaymentEntity existing = existingOpt.get();
            // Validate payload consistency
            if (!existing.getPayerAccountId().equals(request.payerAccountId())
                    || !existing.getMerchantAccountId().equals(request.merchantAccountId())
                    || existing.getAmount().compareTo(request.amount()) != 0
                    || !existing.getCurrency().equals(request.currency())) {
                throw new DuplicateIdempotencyKeyException(
                        "idempotency key already exists for a different payment payload");
            }
            
            LedgerTransactionEntity tx = ledgerTransactionRepository.findByPaymentId(existing.getPaymentId())
                    .orElseThrow(() -> new IllegalStateException("ledger transaction not found for payment: " + existing.getPaymentId()));
            
            PaymentResponse response = new PaymentResponse(
                    existing.getPaymentId(),
                    tx.getTransactionId(),
                    existing.getStatus(),
                    existing.getAmount(),
                    existing.getCurrency(),
                    true, // replayed
                    existing.getCreatedAt().toInstant()
            );
            
            // Rebuild the cache (Redis/InMemory). Since we are not modifying DB, complete immediately
            idempotencyStore.complete(reservation, response);
            return response;
        }

        LedgerWriteResult ledgerWrite = recordPayment(idempotencyKey, request);
        
        PaymentResponse response = new PaymentResponse(
                ledgerWrite.paymentId(),
                ledgerWrite.ledgerTransactionId(),
                "ACCEPTED",
                request.amount(),
                request.currency(),
                false,
                clock.instant()
        );
        
        if (TransactionSynchronizationManager.isActualTransactionActive() && idempotencyStore.requiresAfterCommitCompletion()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    idempotencyStore.complete(reservation, response);
                }
            });
        } else {
            idempotencyStore.complete(reservation, response);
        }
        return response;
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

