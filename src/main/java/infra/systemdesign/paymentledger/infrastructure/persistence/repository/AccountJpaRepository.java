package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountEntity> findByTenantIdAndAccountId(String tenantId, String accountId);

    Optional<AccountEntity> findReadOnlyByTenantIdAndAccountId(String tenantId, String accountId);
}
