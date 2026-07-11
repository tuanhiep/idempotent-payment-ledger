-- =============================================================================
-- Idempotent Payment Ledger - PostgreSQL Migration (V4)
-- Clean up historical test accounts seeded in production migration V2
-- =============================================================================

DELETE FROM accounts 
WHERE account_id IN ('acct-payer', 'acct-merchant', 'acct-payer-http', 'acct-merchant-http');
