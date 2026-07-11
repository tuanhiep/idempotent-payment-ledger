INSERT INTO accounts (account_id, tenant_id, balance, currency)
VALUES
    ('acct-payer', 'default', 1000.0000, 'USD'),
    ('acct-merchant', 'default', 0.0000, 'USD')
ON CONFLICT (account_id) DO NOTHING;
