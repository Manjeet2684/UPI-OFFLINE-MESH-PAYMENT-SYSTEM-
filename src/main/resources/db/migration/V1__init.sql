CREATE TABLE accounts (
    vpa VARCHAR(64) PRIMARY KEY,
    holder_name VARCHAR(128) NOT NULL,
    balance NUMERIC(19, 2) NOT NULL,
    ed25519_public_key VARCHAR(128) NOT NULL,
    version BIGINT,
    CONSTRAINT chk_account_balance_non_negative CHECK (balance >= 0)
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    payment_id VARCHAR(36) NOT NULL UNIQUE,
    packet_hash VARCHAR(64) NOT NULL UNIQUE,
    sender_vpa VARCHAR(64) NOT NULL REFERENCES accounts (vpa),
    receiver_vpa VARCHAR(64) NOT NULL REFERENCES accounts (vpa),
    amount NUMERIC(19, 2) NOT NULL,
    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    settled_at TIMESTAMP WITH TIME ZONE NOT NULL,
    bridge_node_id VARCHAR(64) NOT NULL,
    hop_count INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    CONSTRAINT chk_tx_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_tx_not_self CHECK (sender_vpa <> receiver_vpa)
);

CREATE TABLE delivery_attempts (
    id BIGSERIAL PRIMARY KEY,
    payment_id VARCHAR(36),
    packet_hash VARCHAR(64) NOT NULL,
    packet_id VARCHAR(36),
    bridge_id VARCHAR(64) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason VARCHAR(128),
    transaction_id BIGINT REFERENCES transactions (id),
    path VARCHAR(512),
    hop_count INTEGER
);

CREATE INDEX idx_attempt_payment ON delivery_attempts (payment_id);
CREATE INDEX idx_attempt_hash ON delivery_attempts (packet_hash);
