package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.LedgerEntryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryJpaRepository
        extends JpaRepository<LedgerEntryEntity, String> {

    List<LedgerEntryEntity> findByTransactionId(String transactionId);
}
