CREATE TABLE agent_action_proposal (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    agent_session_id UUID REFERENCES agent_session(id),
    action_name VARCHAR(80) NOT NULL,
    arguments_json TEXT NOT NULL,
    summary TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    request_id VARCHAR(100) NOT NULL UNIQUE,
    provider_id VARCHAR(80) NOT NULL,
    provider_model VARCHAR(160),
    prompt_tokens INTEGER NOT NULL DEFAULT 0,
    completion_tokens INTEGER NOT NULL DEFAULT 0,
    total_tokens INTEGER NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(14, 8) NOT NULL DEFAULT 0,
    result_json TEXT,
    error_code VARCHAR(80),
    confirmed_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_agent_action_user_created
    ON agent_action_proposal(user_id, created_at DESC);

CREATE INDEX idx_agent_action_session_created
    ON agent_action_proposal(agent_session_id, created_at DESC);
