CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(255),
    display_name VARCHAR(120) NOT NULL,
    role VARCHAR(20) NOT NULL,
    demo BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE project (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    name VARCHAR(160) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_project_user_updated ON project(user_id, updated_at DESC);

CREATE TABLE conversation (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES project(id),
    title VARCHAR(200) NOT NULL,
    archived BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_conversation_project_updated ON conversation(project_id, updated_at DESC);

CREATE TABLE image_asset (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL UNIQUE REFERENCES conversation(id),
    storage_key VARCHAR(500) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    mime_type VARCHAR(80) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width_px INTEGER NOT NULL,
    height_px INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE model_release (
    id UUID PRIMARY KEY,
    model_release_id VARCHAR(200) NOT NULL UNIQUE,
    provider_type VARCHAR(40) NOT NULL,
    runtime_mode VARCHAR(20) NOT NULL,
    manifest_json TEXT,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE model_invocation (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    model_release_id VARCHAR(200),
    provider_type VARCHAR(40) NOT NULL,
    prediction_origin VARCHAR(80) NOT NULL,
    question TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    confidence DOUBLE PRECISION,
    margin DOUBLE PRECISION,
    latency_ms BIGINT,
    request_id VARCHAR(100),
    error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE message (
    id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES conversation(id),
    model_invocation_id UUID REFERENCES model_invocation(id),
    role VARCHAR(20) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_message_conversation_created ON message(conversation_id, created_at);

CREATE TABLE agent_run (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    conversation_id UUID REFERENCES conversation(id),
    status VARCHAR(40) NOT NULL,
    input_text TEXT NOT NULL,
    output_text TEXT,
    trace_id VARCHAR(100) NOT NULL,
    latency_ms BIGINT,
    error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE tool_invocation (
    id UUID PRIMARY KEY,
    agent_run_id UUID NOT NULL REFERENCES agent_run(id),
    tool_name VARCHAR(120) NOT NULL,
    arguments_summary TEXT,
    output_summary TEXT,
    status VARCHAR(40) NOT NULL,
    latency_ms BIGINT,
    error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE batch_job (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    project_id UUID REFERENCES project(id),
    model_release_id VARCHAR(200),
    status VARCHAR(40) NOT NULL,
    total_items INTEGER NOT NULL,
    completed_items INTEGER NOT NULL DEFAULT 0,
    failed_items INTEGER NOT NULL DEFAULT 0,
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE batch_item (
    id UUID PRIMARY KEY,
    batch_job_id UUID NOT NULL REFERENCES batch_job(id),
    image_asset_id UUID REFERENCES image_asset(id),
    question TEXT NOT NULL,
    status VARCHAR(40) NOT NULL,
    answer TEXT,
    model_invocation_id UUID REFERENCES model_invocation(id),
    error_code VARCHAR(80),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE knowledge_document (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id),
    title VARCHAR(255) NOT NULL,
    storage_key VARCHAR(500),
    sha256 CHAR(64),
    mime_type VARCHAR(80),
    index_version VARCHAR(80),
    status VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE knowledge_chunk (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES knowledge_document(id),
    ordinal INTEGER NOT NULL,
    content TEXT NOT NULL,
    vector_id VARCHAR(200),
    metadata_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(document_id, ordinal)
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES app_user(id),
    event_type VARCHAR(120) NOT NULL,
    entity_type VARCHAR(80),
    entity_id UUID,
    trace_id VARCHAR(100) NOT NULL,
    outcome VARCHAR(40) NOT NULL,
    summary TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_audit_created ON audit_event(created_at DESC);
