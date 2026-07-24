ALTER TABLE model_invocation
    ADD COLUMN answer TEXT,
    ADD COLUMN predicted_question_type VARCHAR(40),
    ADD COLUMN top_k_json TEXT,
    ADD COLUMN question_type_probabilities_json TEXT,
    ADD COLUMN provider_model VARCHAR(160),
    ADD COLUMN prompt_tokens INTEGER,
    ADD COLUMN completion_tokens INTEGER,
    ADD COLUMN total_tokens INTEGER,
    ADD COLUMN estimated_cost_usd NUMERIC(14, 8);

ALTER TABLE batch_job
    ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE batch_item
    ADD COLUMN margin DOUBLE PRECISION,
    ADD COLUMN predicted_question_type VARCHAR(40),
    ADD COLUMN request_id VARCHAR(100),
    ADD COLUMN model_release_id VARCHAR(200);

CREATE INDEX idx_batch_job_user_archived_created
    ON batch_job(user_id, archived, created_at DESC);

CREATE TABLE user_setting (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES app_user(id),
    locale VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
    reduced_motion BOOLEAN NOT NULL DEFAULT FALSE,
    external_image_opt_in BOOLEAN NOT NULL DEFAULT FALSE,
    settings_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE report (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    project_id UUID REFERENCES project(id),
    batch_job_id UUID REFERENCES batch_job(id),
    title VARCHAR(200) NOT NULL,
    status VARCHAR(40) NOT NULL,
    report_type VARCHAR(40) NOT NULL,
    current_version INTEGER NOT NULL DEFAULT 1,
    request_id VARCHAR(100) NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_report_scope CHECK (
        (project_id IS NOT NULL AND batch_job_id IS NULL)
        OR (project_id IS NULL AND batch_job_id IS NOT NULL)
    )
);
CREATE INDEX idx_report_user_created ON report(user_id, created_at DESC);

CREATE TABLE report_version (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES report(id),
    version_number INTEGER NOT NULL,
    facts_json TEXT NOT NULL,
    markdown_content TEXT NOT NULL,
    agent_summary TEXT,
    citations_json TEXT,
    model_release_id VARCHAR(200),
    prediction_origin VARCHAR(80) NOT NULL,
    generated_by VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(report_id, version_number)
);
