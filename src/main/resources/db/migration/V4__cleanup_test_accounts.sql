-- =============================================================================
-- Idempotent Payment Ledger - PostgreSQL Migration (V4)
-- Clean up historical test accounts seeded in production migration V2
-- =============================================================================

DELETE FROM accounts account
WHERE account.tenant_id = 'default'
  AND account.account_id IN ('acct-payer', 'acct-merchant', 'acct-payer-http', 'acct-merchant-http')
  AND NOT EXISTS (
      SELECT 1
      FROM ledger_entries entry
      WHERE entry.account_id = account.account_id
  );
