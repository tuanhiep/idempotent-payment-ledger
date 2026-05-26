package infra.systemdesign.paymentledger.api;

import static org.assertj.core.api.Assertions.assertThat;

import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("jpa")
class PaymentControllerIntegrationTest extends PostgresIntegrationTestSupport {

    @LocalServerPort
    private int port;

    @Test
    void duplicateHttpRequestIsReplayed() {
        Map<String, Object> request = Map.of(
                "payerAccountId", "acct-payer-http",
                "merchantAccountId", "acct-merchant-http",
                "amount", new BigDecimal("25.00"),
                "currency", "USD"
        );

        Map<?, ?> first = postPayment("http-001", request).body();
        Map<?, ?> replay = postPayment("http-001", request).body();

        assertThat(first.get("replayed")).isEqualTo(false);
        assertThat(replay.get("replayed")).isEqualTo(true);
        assertThat(replay.get("paymentId")).isEqualTo(first.get("paymentId"));
        assertThat(replay.get("ledgerTransactionId")).isEqualTo(first.get("ledgerTransactionId"));
    }

    @Test
    void duplicateHttpKeyWithDifferentPayloadReturnsConflict() {
        postPayment("http-002", Map.of(
                "payerAccountId", "acct-payer-http",
                "merchantAccountId", "acct-merchant-http",
                "amount", new BigDecimal("25.00"),
                "currency", "USD"
        ));

        Response response = postPayment("http-002", Map.of(
                "payerAccountId", "acct-payer-http",
                "merchantAccountId", "acct-merchant-http",
                "amount", new BigDecimal("26.00"),
                "currency", "USD"
        ));

        assertThat(response.status().value()).isEqualTo(409);
        assertThat(response.body().get("code")).isEqualTo("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PAYLOAD");
    }

    private Response postPayment(String idempotencyKey, Map<String, Object> body) {
        RestClient client = RestClient.create("http://localhost:" + port);
        return client.post()
                .uri("/api/payments")
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .exchange((request, response) -> new Response(response.getStatusCode(), response.bodyTo(Map.class)));
    }

    private record Response(HttpStatusCode status, Map<?, ?> body) {}
}
