CREATE TABLE datasets (
    id UUID PRIMARY KEY,
    symbol VARCHAR(64) NOT NULL,
    csv TEXT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    bar_count INTEGER NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT datasets_symbol_valid CHECK (length(btrim(symbol)) > 0 AND symbol = btrim(symbol)),
    CONSTRAINT datasets_csv_not_empty CHECK (length(csv) > 0),
    CONSTRAINT datasets_sha256_valid CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT datasets_bar_count_positive CHECK (bar_count > 0),
    CONSTRAINT datasets_dates_ordered CHECK (start_date <= end_date)
);

CREATE INDEX datasets_created_at_idx ON datasets (created_at DESC, id);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES datasets (id),
    status VARCHAR(16) NOT NULL,
    configuration_json JSONB NOT NULL,
    result_json JSONB,
    failure_code VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT jobs_status_valid CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT jobs_attempts_bounded CHECK (attempts BETWEEN 0 AND 3),
    CONSTRAINT jobs_dates_ordered CHECK (created_at <= updated_at),
    CONSTRAINT jobs_configuration_object CHECK (jsonb_typeof(configuration_json) = 'object'),
    CONSTRAINT jobs_result_object CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object'),
    CONSTRAINT jobs_failure_code_valid CHECK (failure_code IS NULL OR failure_code ~ '^[A-Z][A-Z0-9_]{0,127}$'),
    CONSTRAINT jobs_lease_state_valid CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL AND lease_until > updated_at AND attempts >= 1)
        OR (status <> 'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT jobs_terminal_data_valid CHECK (
        (status = 'SUCCEEDED' AND result_json IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND result_json IS NULL AND failure_code IS NOT NULL)
        OR (status IN ('PENDING', 'RUNNING', 'CANCELLED') AND result_json IS NULL AND failure_code IS NULL)
    ),
    CONSTRAINT jobs_pending_attempts_valid CHECK (status <> 'PENDING' OR attempts = 0)
);

CREATE INDEX jobs_created_at_idx ON jobs (created_at DESC, id);
CREATE INDEX jobs_dataset_id_idx ON jobs (dataset_id);
CREATE INDEX jobs_claimable_idx ON jobs (status, created_at, id) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX jobs_expired_leases_idx ON jobs (lease_until) WHERE status = 'RUNNING';
CREATE UNIQUE INDEX jobs_active_lease_token_idx ON jobs (lease_token) WHERE lease_token IS NOT NULL;
