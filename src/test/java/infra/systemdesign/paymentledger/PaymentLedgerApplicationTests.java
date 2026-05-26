package infra.systemdesign.paymentledger;

import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("jpa")
class PaymentLedgerApplicationTests extends PostgresIntegrationTestSupport {

    @Test
    void contextLoads() {
    }
}
