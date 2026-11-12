ALTER TABLE jobs ADD COLUMN available_at TIMESTAMPTZ;
UPDATE jobs SET available_at = created_at;
ALTER TABLE jobs ALTER COLUMN available_at SET NOT NULL;

ALTER TABLE jobs DROP CONSTRAINT jobs_status_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_status_valid
    CHECK (status IN ('PENDING', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'));
ALTER TABLE jobs DROP CONSTRAINT jobs_terminal_data_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_terminal_data_valid CHECK (
    (status = 'SUCCEEDED' AND result_json IS NOT NULL AND failure_code IS NULL)
    OR (status = 'FAILED' AND result_json IS NULL AND failure_code IS NOT NULL)
    OR (status IN ('PENDING', 'QUEUED', 'RUNNING', 'CANCELLED') AND result_json IS NULL AND failure_code IS NULL)
);
ALTER TABLE jobs DROP CONSTRAINT jobs_pending_attempts_valid;
ALTER TABLE jobs ADD CONSTRAINT jobs_pending_attempts_valid
    CHECK (status NOT IN ('PENDING', 'QUEUED') OR attempts < 3);

DROP INDEX jobs_claimable_idx;
CREATE INDEX jobs_claimable_idx ON jobs (status, available_at, created_at, id)
    WHERE status IN ('PENDING', 'QUEUED', 'RUNNING');

CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    kind VARCHAR(16) NOT NULL,
    failure_code VARCHAR(128),
    job_attempt INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    failed_at TIMESTAMPTZ,
    CONSTRAINT outbox_kind_valid CHECK (kind IN ('BACKTEST', 'DEAD_LETTER')),
    CONSTRAINT outbox_job_attempt_bounded CHECK (job_attempt BETWEEN 0 AND 3),
    CONSTRAINT outbox_attempts_bounded CHECK (attempts BETWEEN 0 AND 10),
    CONSTRAINT outbox_available_ordered CHECK (created_at <= available_at),
    CONSTRAINT outbox_publication_state_valid CHECK (published_at IS NULL OR failed_at IS NULL),
    CONSTRAINT outbox_failure_valid CHECK (
        (kind = 'BACKTEST' AND failure_code IS NULL)
        OR (kind = 'DEAD_LETTER' AND failure_code IS NOT NULL AND failure_code ~ '^[A-Z][A-Z0-9_]{0,127}$')
    ),
    CONSTRAINT outbox_lease_valid CHECK (
        (lease_token IS NULL AND lease_until IS NULL)
        OR (lease_token IS NOT NULL AND lease_until IS NOT NULL AND last_attempt_at IS NOT NULL
            AND lease_until > last_attempt_at AND attempts > 0 AND published_at IS NULL AND failed_at IS NULL)
    )
);

CREATE INDEX outbox_available_idx ON outbox (available_at, created_at, id)
    WHERE published_at IS NULL AND failed_at IS NULL;
CREATE INDEX outbox_expired_idx ON outbox (lease_until)
    WHERE published_at IS NULL AND failed_at IS NULL;
CREATE INDEX outbox_job_idx ON outbox (job_id);
CREATE UNIQUE INDEX outbox_active_lease_idx ON outbox (lease_token) WHERE lease_token IS NOT NULL;

-- Carry pending jobs from the local M2 executor into the publication workflow on upgrade.
INSERT INTO outbox (id, job_id, kind, job_attempt, attempts, created_at, available_at)
SELECT gen_random_uuid(), id, 'BACKTEST', attempts, 0, created_at, available_at
FROM jobs WHERE status = 'PENDING';

CREATE TABLE batches (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE batch_jobs (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES batches (id) ON DELETE CASCADE,
    job_id UUID NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    CONSTRAINT batch_jobs_position_valid CHECK (position BETWEEN 0 AND 99),
    CONSTRAINT batch_jobs_position_unique UNIQUE (batch_id, position),
    CONSTRAINT batch_jobs_job_unique UNIQUE (job_id)
);

CREATE INDEX batches_created_at_idx ON batches (created_at DESC, id);
