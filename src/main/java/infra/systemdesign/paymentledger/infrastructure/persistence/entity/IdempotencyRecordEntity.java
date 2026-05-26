package infra.systemdesign.paymentledger.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;

/**
 * JPA entity for the idempotency_records table.
 *
 * <p>The UNIQUE constraint on (tenant_id, idempotency_key) is the primary race condition
 * guard. Two concurrent requests race on INSERT; one wins, one receives a constraint
 * violation and falls back to reading the existing record.
 *
 * <p>Status transitions: PROCESSING → ACCEPTED (normal path) | PROCESSING → FAILED (error path).
 * PROCESSING records with no completed_at after a timeout indicate in-flight requests
 * that may need cleanup — see ADR-001 for the planned strategy.
 */
@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency_key",
                columnNames = {"tenant_id", "idempotency_key"}
        )
)
public class IdempotencyRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false)
    private String payloadHash;

    // Stored as JSON text for audit and reconciliation.
    @Column(name = "request_body", nullable = false, columnDefinition = "TEXT")
    private String requestBody;

    // NULL while PROCESSING; populated when ACCEPTED or FAILED.
    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    // PROCESSING | ACCEPTED | FAILED
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    // Required by JPA
    protected IdempotencyRecordEntity() {}

    public IdempotencyRecordEntity(
            String tenantId,
            String idempotencyKey,
            String payloadHash,
            String requestBody,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
        this.requestBody = requestBody;
        this.status = status;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }
    public String getRequestBody() { return requestBody; }
    public String getResponseBody() { return responseBody; }
    public String getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    public void setStatus(String status) { this.status = status; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }
}
