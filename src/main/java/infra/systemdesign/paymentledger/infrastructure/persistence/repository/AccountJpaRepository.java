package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface AccountJpaRepository extends JpaRepository<AccountEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountEntity> findByTenantIdAndAccountId(String tenantId, String accountId);

    @org.springframework.data.jpa.repository.Query("SELECT a FROM AccountEntity a WHERE a.tenantId = :tenantId AND a.accountId = :accountId")
    Optional<AccountEntity> findAccountByTenantIdAndAccountId(
            @org.springframework.data.repository.query.Param("tenantId") String tenantId,
            @org.springframework.data.repository.query.Param("accountId") String accountId);
}
