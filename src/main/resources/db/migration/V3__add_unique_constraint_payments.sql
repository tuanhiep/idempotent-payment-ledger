-- =============================================================================
-- Idempotent Payment Ledger - PostgreSQL Migration (V3)
-- Defense-in-Depth: Add UNIQUE constraint to payments table for idempotency_key
-- =============================================================================

-- Drop the redundant non-unique index if it exists
DROP INDEX IF EXISTS idx_payments_idempotency_key;

-- Add the unique constraint to enforce physical database-level idempotency protection
ALTER TABLE payments ADD CONSTRAINT uq_payments_key UNIQUE (tenant_id, idempotency_key);
