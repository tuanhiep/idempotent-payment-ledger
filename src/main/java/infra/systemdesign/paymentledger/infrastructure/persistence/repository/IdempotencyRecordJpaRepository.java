package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.IdempotencyRecordEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

public interface IdempotencyRecordJpaRepository
        extends JpaRepository<IdempotencyRecordEntity, Long> {

    Optional<IdempotencyRecordEntity> findByTenantIdAndIdempotencyKey(
            String tenantId, String idempotencyKey);

    // Explicit @Modifying + @Transactional required for derived delete queries
    // in Spring Data JPA. Called from JpaIdempotencyStore.fail() via REQUIRES_NEW.
    @Modifying
    @Transactional
    void deleteByTenantIdAndIdempotencyKey(String tenantId, String idempotencyKey);
}
