CREATE TABLE agent_session (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    project_id UUID REFERENCES project(id),
    conversation_id UUID REFERENCES conversation(id),
    batch_job_id UUID REFERENCES batch_job(id),
    title VARCHAR(200) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_agent_session_context CHECK (
        ((project_id IS NOT NULL)::int
        + (conversation_id IS NOT NULL)::int
        + (batch_job_id IS NOT NULL)::int) <= 1
    )
);

CREATE INDEX idx_agent_session_user_archived_updated
    ON agent_session(user_id, archived, updated_at DESC);

ALTER TABLE agent_run
    ADD COLUMN agent_session_id UUID REFERENCES agent_session(id),
    ADD COLUMN project_id UUID REFERENCES project(id),
    ADD COLUMN batch_job_id UUID REFERENCES batch_job(id),
    ADD COLUMN provider_id VARCHAR(80),
    ADD COLUMN provider_model VARCHAR(160),
    ADD COLUMN prompt_tokens INTEGER,
    ADD COLUMN completion_tokens INTEGER,
    ADD COLUMN total_tokens INTEGER,
    ADD COLUMN estimated_cost_usd NUMERIC(14, 8);

CREATE INDEX idx_agent_run_session_created
    ON agent_run(agent_session_id, created_at);
