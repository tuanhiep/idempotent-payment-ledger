package infra.systemdesign.paymentledger.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA entity for the ledger_entries table.
 *
 * <p>Individual debit and credit postings for a ledger transaction.
 * Every committed ledger_transaction must have entries that sum to zero:
 * SUM(CREDIT amounts) - SUM(DEBIT amounts) = 0
 *
 * <p>amount is always positive; the sign is carried by entry_type (DEBIT | CREDIT).
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    @Column(name = "entry_id")
    private String entryId;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    // DEBIT | CREDIT
    @Column(name = "entry_type", nullable = false, length = 8)
    private String entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Required by JPA
    protected LedgerEntryEntity() {}

    public LedgerEntryEntity(
            String entryId,
            String transactionId,
            String accountId,
            String entryType,
            BigDecimal amount,
            String currency,
            OffsetDateTime createdAt) {
        this.entryId = entryId;
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.entryType = entryType;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getEntryId() { return entryId; }
    public String getTransactionId() { return transactionId; }
    public String getAccountId() { return accountId; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
