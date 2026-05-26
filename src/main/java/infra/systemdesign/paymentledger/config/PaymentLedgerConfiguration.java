package infra.systemdesign.paymentledger.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class PaymentLedgerConfiguration {

    @Bean
    Clock paymentLedgerClock() {
        return Clock.systemUTC();
    }

    @Bean
    ObjectMapper paymentLedgerObjectMapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .build();
    }
}
