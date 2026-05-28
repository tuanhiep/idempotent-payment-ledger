package infra.systemdesign.paymentledger.application.port;

import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import infra.systemdesign.paymentledger.domain.PaymentResponse;
import java.math.BigDecimal;
import java.util.List;

public interface LedgerStore {

    LedgerWriteResult recordPayment(String idempotencyKey, PaymentRequest request);

    PaymentResponse recordPaymentAndComplete(
            String idempotencyKey,
            PaymentRequest request,
            IdempotencyStore.NewReservation reservation
    );

    List<LedgerEntry> entriesForTransaction(String transactionId);


    BigDecimal balanceForTransaction(String transactionId);

    int entryCount();

    BigDecimal getAccountBalance(String accountId);

    record LedgerWriteResult(String paymentId, String ledgerTransactionId) {}
}
