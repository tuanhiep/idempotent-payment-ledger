package infra.systemdesign.paymentledger.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA entity for the accounts table.
 *
 * <p>Represents the aggregated static balance of a payment participant.
 * Mutated under Pessimistic Write Lock to guarantee overdraft protection under concurrency.
 */
@Entity
@Table(name = "accounts")
public class AccountEntity {

    @Id
    @Column(name = "account_id")
    private String accountId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    // Required by JPA
    protected AccountEntity() {}

    public AccountEntity(
            String accountId,
            String tenantId,
            BigDecimal balance,
            String currency,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        this.accountId = accountId;
        this.tenantId = tenantId;
        this.balance = balance;
        this.currency = currency;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getAccountId() { return accountId; }
    public String getTenantId() { return tenantId; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public String getCurrency() { return currency; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
