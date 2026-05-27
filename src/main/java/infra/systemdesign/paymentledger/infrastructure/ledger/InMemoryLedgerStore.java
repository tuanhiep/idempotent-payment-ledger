package infra.systemdesign.paymentledger.infrastructure.ledger;

import infra.systemdesign.paymentledger.application.port.LedgerStore;
import infra.systemdesign.paymentledger.domain.InsufficientFundsException;
import infra.systemdesign.paymentledger.domain.LedgerEntry;
import infra.systemdesign.paymentledger.domain.LedgerEntryType;
import infra.systemdesign.paymentledger.domain.PaymentRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("!jpa")
public class InMemoryLedgerStore implements LedgerStore {

    private final List<LedgerEntry> entries = new ArrayList<>();
    private final Map<String, BigDecimal> balances = new HashMap<>();
    private final Lock lock = new ReentrantLock();
    private final Clock clock;

    public InMemoryLedgerStore(Clock clock) {
        this.clock = clock;
        // Khởi tạo sẵn tài khoản test cho local tests
        balances.put("acct-payer", new BigDecimal("1000.0000"));
        balances.put("acct-merchant", BigDecimal.ZERO);
        balances.put("acct-payer-http", new BigDecimal("1000.0000"));
        balances.put("acct-merchant-http", BigDecimal.ZERO);
    }

    public LedgerWriteResult recordPayment(String idempotencyKey, PaymentRequest request) {
        lock.lock();
        try {
            BigDecimal payerBalance = balances.getOrDefault(request.payerAccountId(), BigDecimal.ZERO);
            if (payerBalance.compareTo(request.amount()) < 0) {
                throw new InsufficientFundsException(
                        "insufficient funds in payer account: " + request.payerAccountId());
            }

            // Trừ tiền payer, cộng tiền merchant
            balances.put(request.payerAccountId(), payerBalance.subtract(request.amount()));
            BigDecimal merchantBalance = balances.getOrDefault(request.merchantAccountId(), BigDecimal.ZERO);
            balances.put(request.merchantAccountId(), merchantBalance.add(request.amount()));

            String paymentId = UUID.randomUUID().toString();
            String transactionId = UUID.randomUUID().toString();
            Instant now = clock.instant();
            entries.add(new LedgerEntry(
                    UUID.randomUUID().toString(),
                    transactionId,
                    request.payerAccountId(),
                    LedgerEntryType.DEBIT,
                    request.amount(),
                    request.currency(),
                    now
            ));
            entries.add(new LedgerEntry(
                    UUID.randomUUID().toString(),
                    transactionId,
                    request.merchantAccountId(),
                    LedgerEntryType.CREDIT,
                    request.amount(),
                    request.currency(),
                    now
            ));
            return new LedgerWriteResult(paymentId, transactionId);
        } finally {
            lock.unlock();
        }
    }

    public List<LedgerEntry> entriesForTransaction(String transactionId) {
        lock.lock();
        try {
            return entries.stream()
                    .filter(entry -> entry.transactionId().equals(transactionId))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public BigDecimal balanceForTransaction(String transactionId) {
        lock.lock();
        try {
            return entries.stream()
                    .filter(entry -> entry.transactionId().equals(transactionId))
                    .map(entry -> entry.type() == LedgerEntryType.CREDIT ? entry.amount() : entry.amount().negate())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } finally {
            lock.unlock();
        }
    }

    public int entryCount() {
        lock.lock();
        try {
            return entries.size();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BigDecimal getAccountBalance(String accountId) {
        lock.lock();
        try {
            return balances.getOrDefault(accountId, BigDecimal.ZERO);
        } finally {
            lock.unlock();
        }
    }
}
