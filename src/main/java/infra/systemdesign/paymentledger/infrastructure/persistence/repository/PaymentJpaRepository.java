package infra.systemdesign.paymentledger.infrastructure.persistence.repository;

import infra.systemdesign.paymentledger.infrastructure.persistence.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<PaymentEntity, String> {
}
