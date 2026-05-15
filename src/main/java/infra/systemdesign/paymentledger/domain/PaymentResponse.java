package infra.systemdesign.paymentledger.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentResponse(
        String paymentId,
        String ledgerTransactionId,
        String status,
        BigDecimal amount,
        String currency,
        boolean replayed,
        Instant processedAt
) {

    PaymentResponse asReplay() {
        return new PaymentResponse(
                paymentId,
                ledgerTransactionId,
                status,
                amount,
                currency,
                true,
                processedAt
        );
    }
}
