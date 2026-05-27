-- =============================================================================
-- Idempotent Payment Ledger - PostgreSQL Migration (V2)
-- Durable Balance Check & Overdraft Protection (Double-Spending Prevention)
-- =============================================================================

CREATE TABLE IF NOT EXISTS accounts (
    account_id  TEXT            NOT NULL,
    tenant_id   TEXT            NOT NULL,
    balance     NUMERIC(19, 4)  NOT NULL DEFAULT 0.0000,
    currency    VARCHAR(3)      NOT NULL,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_accounts PRIMARY KEY (account_id)
);

-- Ràng buộc số dư không được âm để bảo vệ ở tầng cứng database
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_balance_non_negative CHECK (balance >= 0);

-- Thiết lập khóa ngoại từ ledger_entries sang accounts
ALTER TABLE ledger_entries ADD CONSTRAINT fk_ledger_entries_account 
    FOREIGN KEY (account_id) REFERENCES accounts (account_id);

-- Khởi tạo sẵn tài khoản test cho Integration Tests
INSERT INTO accounts (account_id, tenant_id, balance, currency)
VALUES 
    ('acct-payer', 'default', 1000.0000, 'USD'),
    ('acct-merchant', 'default', 0.0000, 'USD'),
    ('acct-payer-http', 'default', 1000.0000, 'USD'),
    ('acct-merchant-http', 'default', 0.0000, 'USD')
ON CONFLICT (account_id) DO NOTHING;
