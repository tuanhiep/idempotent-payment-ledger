package infra.systemdesign.paymentledger.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA entity for the payments table.
 *
 * <p>One row per accepted logical payment. Immutable after INSERT — never mutated.
 * The payment_id is application-generated (UUID) rather than DB-generated to allow
 * the ID to be known before the row is persisted.
 */
@Entity
@Table(name = "payments")
public class PaymentEntity {

    @Id
    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payer_account_id", nullable = false)
    private String payerAccountId;

    @Column(name = "merchant_account_id", nullable = false)
    private String merchantAccountId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    // ACCEPTED | REVERSED
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Required by JPA
    protected PaymentEntity() {}

    public PaymentEntity(
            String paymentId,
            String tenantId,
            String idempotencyKey,
            String payerAccountId,
            String merchantAccountId,
            BigDecimal amount,
            String currency,
            String status,
            OffsetDateTime createdAt) {
        this.paymentId = paymentId;
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.payerAccountId = payerAccountId;
        this.merchantAccountId = merchantAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getPaymentId() { return paymentId; }
    public String getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayerAccountId() { return payerAccountId; }
    public String getMerchantAccountId() { return merchantAccountId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
