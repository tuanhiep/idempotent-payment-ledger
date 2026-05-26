package infra.systemdesign.paymentledger.application.port;

import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import java.math.BigDecimal;
import java.util.List;

public interface LedgerStore {

    LedgerWriteResult recordPayment(String idempotencyKey, PaymentRequest request);

    List<LedgerEntry> entriesForTransaction(String transactionId);

    BigDecimal balanceForTransaction(String transactionId);

    int entryCount();

    record LedgerWriteResult(String paymentId, String ledgerTransactionId) {}
}
