-- Users and jobs live in the backend database, so there are no foreign keys: the job is checked through the backend
-- when an interaction is recorded, and rows of deleted users or jobs are simply ignored by the graph sync.
CREATE TABLE IF NOT EXISTS user_job_interaction (
    user_id            UUID        NOT NULL,
    job_id             UUID        NOT NULL,
    interaction_type   VARCHAR(20) NOT NULL,
    interaction_count  INT         NOT NULL DEFAULT 1,
    first_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pk_user_job_interaction PRIMARY KEY (user_id, job_id, interaction_type),
    CONSTRAINT ck_user_job_interaction_type CHECK (interaction_type IN ('VIEWED', 'APPLY_CLICKED'))
);
