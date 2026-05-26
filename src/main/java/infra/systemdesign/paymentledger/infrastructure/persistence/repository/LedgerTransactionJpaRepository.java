package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerTransactionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionJpaRepository
        extends JpaRepository<LedgerTransactionEntity, String> {
}
