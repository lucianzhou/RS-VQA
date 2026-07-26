-- RS-Bot run provenance.
--
-- The same question can be answered by LLM planning or by the deterministic
-- rule-based fallback, and those are not interchangeable evidence. Recording the
-- orchestration mode, prompt version, stop reason and step count makes a stored
-- answer attributable after the fact.

ALTER TABLE agent_run
    ADD COLUMN provider_state VARCHAR(40),
    ADD COLUMN prompt_version VARCHAR(40),
    ADD COLUMN stop_reason VARCHAR(40),
    ADD COLUMN tool_steps INTEGER;

CREATE INDEX idx_agent_run_provider_state ON agent_run (provider_state, prompt_version);
