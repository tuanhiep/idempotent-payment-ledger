package infra.systemdesign.paymentledger.domain;

public record IdempotencyRecord(
        String key,
        String payloadHash,
        PaymentResponse response
) {

    public PaymentResponse replay() {
        return response.asReplay();
    }
}
