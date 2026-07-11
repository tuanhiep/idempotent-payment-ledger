package infra.systemdesign.paymentledger.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import infra.systemdesign.paymentledger.support.PostgresIntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

class FlywayMigrationUpgradeTest extends PostgresIntegrationTestSupport {

    @Test
    void v4CleanupPreservesHistoricalAccountsReferencedByLedgerEntries() throws SQLException {
        String schema = "upgrade_" + UUID.randomUUID().toString().replace("-", "");

        try {
            flyway(schema, MigrationVersion.fromVersion("3")).migrate();
            seedHistoricalLedgerData(schema);

            flyway(schema, null).migrate();

            assertThat(accountIds(schema))
                    .containsExactly("acct-merchant", "acct-payer");
        } finally {
            dropSchema(schema);
        }
    }

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgresJdbcUrl(), postgresUsername(), postgresPassword())
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void seedHistoricalLedgerData(String schema) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO payments (
                        payment_id, tenant_id, idempotency_key, payer_account_id,
                        merchant_account_id, amount, currency, status
                    ) VALUES (
                        'payment-before-v4', 'default', 'key-before-v4', 'acct-payer',
                        'acct-merchant', 100.0000, 'USD', 'ACCEPTED'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO ledger_transactions (
                        transaction_id, payment_id, posting_rule, posting_rule_version, status
                    ) VALUES (
                        'transaction-before-v4', 'payment-before-v4', 'PAYMENT_ACCEPTED', 1, 'POSTED'
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO ledger_entries (
                        entry_id, transaction_id, account_id, entry_type, amount, currency
                    ) VALUES
                        ('entry-debit-before-v4', 'transaction-before-v4', 'acct-payer', 'DEBIT', 100.0000, 'USD'),
                        ('entry-credit-before-v4', 'transaction-before-v4', 'acct-merchant', 'CREDIT', 100.0000, 'USD')
                    """);
        }
    }

    private static List<String> accountIds(String schema) throws SQLException {
        try (Connection connection = connection(schema);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT account_id FROM accounts ORDER BY account_id")) {
            List<String> accountIds = new ArrayList<>();
            while (resultSet.next()) {
                accountIds.add(resultSet.getString("account_id"));
            }
            return accountIds;
        }
    }

    private static Connection connection(String schema) throws SQLException {
        Connection connection = DriverManager.getConnection(
                postgresJdbcUrl(), postgresUsername(), postgresPassword());
        connection.setSchema(schema);
        return connection;
    }

    private static void dropSchema(String schema) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                postgresJdbcUrl(), postgresUsername(), postgresPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }
}
