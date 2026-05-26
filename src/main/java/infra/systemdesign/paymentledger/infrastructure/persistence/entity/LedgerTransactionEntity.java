package infra.systemdesign.paymentledger.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

/**
 * JPA entity for the ledger_transactions table.
 *
 * <p>Groups balanced ledger entries under a single accounting event.
 * Captures the posting rule and version so auditors can reconstruct why
 * entries were written exactly as they were.
 */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionEntity {

    @Id
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "payment_id", nullable = false)
    private String paymentId;

    // e.g. PAYMENT_ACCEPTED — identifies which posting rule was applied
    @Column(name = "posting_rule", nullable = false)
    private String postingRule;

    @Column(name = "posting_rule_version", nullable = false)
    private int postingRuleVersion;

    // POSTED | REVERSED
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Required by JPA
    protected LedgerTransactionEntity() {}

    public LedgerTransactionEntity(
            String transactionId,
            String paymentId,
            String postingRule,
            int postingRuleVersion,
            String status,
            OffsetDateTime createdAt) {
        this.transactionId = transactionId;
        this.paymentId = paymentId;
        this.postingRule = postingRule;
        this.postingRuleVersion = postingRuleVersion;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getTransactionId() { return transactionId; }
    public String getPaymentId() { return paymentId; }
    public String getPostingRule() { return postingRule; }
    public int getPostingRuleVersion() { return postingRuleVersion; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
