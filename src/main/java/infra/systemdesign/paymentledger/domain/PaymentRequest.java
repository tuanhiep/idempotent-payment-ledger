package infra.systemdesign.paymentledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

public record PaymentRequest(
        String payerAccountId,
        String merchantAccountId,
        BigDecimal amount,
        String currency
) {

    public PaymentRequest canonical() {
        if (payerAccountId == null || payerAccountId.isBlank()) {
            throw new IllegalArgumentException("payerAccountId must not be blank");
        }
        if (merchantAccountId == null || merchantAccountId.isBlank()) {
            throw new IllegalArgumentException("merchantAccountId must not be blank");
        }
        if (payerAccountId.equals(merchantAccountId)) {
            throw new IllegalArgumentException("payerAccountId and merchantAccountId must be different");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        return new PaymentRequest(
                payerAccountId.trim(),
                merchantAccountId.trim(),
                amount.setScale(2, RoundingMode.UNNECESSARY),
                currency.trim().toUpperCase(Locale.ROOT)
        );
    }

    public String payloadFingerprint() {
        PaymentRequest canonical = canonical();
        return String.join("|",
                canonical.payerAccountId(),
                canonical.merchantAccountId(),
                canonical.amount().toPlainString(),
                canonical.currency()
        );
    }
}
