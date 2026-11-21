-- =====================================================================
-- SpeakPath AI 核心 Schema（PostgreSQL 16 基线）
-- 说明：
--   1. 主键 UUID，统一 created_at/updated_at；音频存对象存储，DB 仅存对象 key。
--   2. 与《研发任务分解与接口实现规格书》§5 对齐；JSONB 用于版本化配置与模型结果。
--   3. 生产按“先扩展后收缩”演进；本脚本为初始基线，禁止破坏性回改。
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------
-- Identity
-- ---------------------------------------------------------------------
CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         VARCHAR(254) UNIQUE,
    phone         VARCHAR(32) UNIQUE,
    password_hash VARCHAR(255),
    display_name  VARCHAR(80),
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE','LOCKED','PENDING_DELETE','DELETED')),
    locale        VARCHAR(16) NOT NULL DEFAULT 'en-US',
    timezone      VARCHAR(64) NOT NULL DEFAULT 'UTC',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (email IS NOT NULL OR phone IS NOT NULL)
);

CREATE TABLE user_devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID NOT NULL REFERENCES users(id),
    platform           VARCHAR(16) NOT NULL,
    device_name        VARCHAR(120),
    refresh_token_hash VARCHAR(255) NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    revoked_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_devices_user ON user_devices(user_id);

-- rest_days 用 JSONB（周几数组，0=周日），与前端 JS getDay() 约定一致
CREATE TABLE user_preferences (
    user_id         UUID PRIMARY KEY REFERENCES users(id),
    daily_minutes   INT NOT NULL DEFAULT 30 CHECK (daily_minutes BETWEEN 5 AND 300),
    rest_days       JSONB NOT NULL DEFAULT '[]',
    target_exam     VARCHAR(40),
    target_score    NUMERIC(4,1),
    exam_date       DATE,
    preference_json JSONB NOT NULL DEFAULT '{}',
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE consent_records (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    consent_type VARCHAR(40) NOT NULL,
    version      VARCHAR(40) NOT NULL,
    granted      BOOLEAN NOT NULL DEFAULT TRUE,
    payload_json JSONB NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_user ON consent_records(user_id, consent_type);

-- ---------------------------------------------------------------------
-- Exam catalog
-- ---------------------------------------------------------------------
CREATE TABLE exam_templates (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_code    VARCHAR(40) NOT NULL,
    version      INT NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    config_json  JSONB NOT NULL,
    rubric_json  JSONB NOT NULL,
    status       VARCHAR(20) NOT NULL
        CHECK (status IN ('DRAFT','REVIEW','PUBLISHED','ARCHIVED')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (exam_code, version)
);

CREATE TABLE question_bank (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_code         VARCHAR(40) NOT NULL,
    section_code      VARCHAR(40) NOT NULL,
    question_text     TEXT NOT NULL,
    difficulty        SMALLINT NOT NULL CHECK (difficulty BETWEEN 1 AND 10),
    estimated_seconds INT NOT NULL DEFAULT 60,
    tags              JSONB NOT NULL DEFAULT '[]',
    status            VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    version           INT NOT NULL DEFAULT 1,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_question_search ON question_bank(exam_code, section_code, status, difficulty);

-- ---------------------------------------------------------------------
-- Assessment
-- ---------------------------------------------------------------------
CREATE TABLE assessment_sessions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    exam_template_id UUID NOT NULL REFERENCES exam_templates(id),
    exam_code        VARCHAR(40) NOT NULL,
    exam_version     INT NOT NULL,
    rubric_version   INT NOT NULL,
    status           VARCHAR(24) NOT NULL,
    target_score     NUMERIC(4,1),
    turn_count       SMALLINT NOT NULL DEFAULT 0 CHECK (turn_count BETWEEN 0 AND 15),
    max_turns        SMALLINT NOT NULL DEFAULT 15 CHECK (max_turns BETWEEN 10 AND 15),
    expires_at       TIMESTAMPTZ,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assessment_user_created ON assessment_sessions(user_id, created_at DESC);

CREATE TABLE assessment_turns (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id         UUID NOT NULL REFERENCES assessment_sessions(id),
    sequence_no        SMALLINT NOT NULL CHECK (sequence_no BETWEEN 1 AND 15),
    question_id        UUID REFERENCES question_bank(id),
    question_snapshot  JSONB NOT NULL,
    answer_text        TEXT,
    transcript_json    JSONB,
    audio_object_key   TEXT,
    audio_duration_ms  INT,
    audio_quality_json JSONB,
    analysis_json      JSONB,
    status             VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, sequence_no)
);

CREATE TABLE assessment_reports (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id       UUID NOT NULL UNIQUE REFERENCES assessment_sessions(id),
    rubric_version   INT NOT NULL,
    report_json      JSONB NOT NULL,
    overall_low      NUMERIC(4,1),
    overall_high     NUMERIC(4,1),
    confidence       NUMERIC(4,3) CHECK (confidence BETWEEN 0 AND 1),
    model_trace_json JSONB NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------
-- Learning plan
-- ---------------------------------------------------------------------
CREATE TABLE study_plans (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    source_report_id UUID REFERENCES assessment_reports(id),
    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    start_date       DATE NOT NULL,
    end_date         DATE NOT NULL,
    active_version   INT NOT NULL DEFAULT 1,
    goal_json        JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_study_plan_user_status ON study_plans(user_id, status);

CREATE TABLE plan_versions (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id       UUID NOT NULL REFERENCES study_plans(id),
    version       INT NOT NULL,
    plan_json     JSONB NOT NULL,
    change_reason VARCHAR(80) NOT NULL,
    diff_json     JSONB NOT NULL DEFAULT '{}',
    created_by    VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (plan_id, version)
);

CREATE TABLE learning_tasks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_version_id     UUID NOT NULL REFERENCES plan_versions(id),
    user_id             UUID NOT NULL REFERENCES users(id),
    task_date           DATE NOT NULL,
    task_type           VARCHAR(40) NOT NULL,
    title               VARCHAR(180) NOT NULL,
    duration_min        SMALLINT NOT NULL CHECK (duration_min BETWEEN 1 AND 300),
    priority            SMALLINT NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),
    status              VARCHAR(20) NOT NULL DEFAULT 'TODO',
    is_locked           BOOLEAN NOT NULL DEFAULT FALSE,
    content_json        JSONB NOT NULL DEFAULT '{}',
    actual_duration_min SMALLINT,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_tasks_user_date ON learning_tasks(user_id, task_date, status);

-- ---------------------------------------------------------------------
-- Speaking
-- ---------------------------------------------------------------------
CREATE TABLE speaking_sessions (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL REFERENCES users(id),
    task_id        UUID REFERENCES learning_tasks(id),
    provider_code  VARCHAR(40) NOT NULL,
    model_code     VARCHAR(120) NOT NULL,
    status         VARCHAR(24) NOT NULL,
    transport      VARCHAR(20) NOT NULL,
    started_at     TIMESTAMPTZ,
    ended_at       TIMESTAMPTZ,
    duration_ms    BIGINT,
    summary_job_id UUID,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_speaking_user_created ON speaking_sessions(user_id, created_at DESC);

CREATE TABLE speaking_turns (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL REFERENCES speaking_sessions(id),
    sequence_no       INT NOT NULL,
    role              VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT')),
    transcript        TEXT,
    transcript_json   JSONB,
    audio_object_key  TEXT,
    duration_ms       INT,
    metadata_json     JSONB NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (session_id, sequence_no)
);

CREATE TABLE correction_items (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    speaking_turn_id UUID NOT NULL REFERENCES speaking_turns(id),
    category         VARCHAR(32) NOT NULL,
    severity         VARCHAR(16) NOT NULL,
    original_text    TEXT NOT NULL,
    corrected_text   TEXT NOT NULL,
    explanation      TEXT NOT NULL,
    evidence_json    JSONB NOT NULL DEFAULT '{}',
    confidence       NUMERIC(4,3),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_correction_turn ON correction_items(speaking_turn_id);

-- ---------------------------------------------------------------------
-- AI
-- ---------------------------------------------------------------------
CREATE TABLE ai_model_configs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capability         VARCHAR(32) NOT NULL,
    provider_code      VARCHAR(40) NOT NULL,
    model_code         VARCHAR(120) NOT NULL,
    base_url           TEXT,
    secret_ref         VARCHAR(255),
    priority           INT NOT NULL DEFAULT 100,
    enabled            BOOLEAN NOT NULL DEFAULT FALSE,
    timeout_ms         INT NOT NULL DEFAULT 10000,
    max_retries        SMALLINT NOT NULL DEFAULT 1,
    fallback_config_id UUID REFERENCES ai_model_configs(id),
    parameters_json    JSONB NOT NULL DEFAULT '{}',
    region             VARCHAR(32),
    data_policy        VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    traffic_percent    SMALLINT NOT NULL DEFAULT 100 CHECK (traffic_percent BETWEEN 0 AND 100),
    version            INT NOT NULL DEFAULT 1,
    status             VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_config_route ON ai_model_configs(capability, enabled, status, priority);

CREATE TABLE ai_call_logs (
    id             BIGSERIAL PRIMARY KEY,
    trace_id       VARCHAR(64) NOT NULL,
    user_id        UUID REFERENCES users(id),
    session_id     UUID,
    capability     VARCHAR(32) NOT NULL,
    provider_code  VARCHAR(40) NOT NULL,
    model_code     VARCHAR(120) NOT NULL,
    config_version INT NOT NULL,
    prompt_version VARCHAR(80),
    latency_ms     INT,
    status         VARCHAR(20) NOT NULL,
    usage_json     JSONB NOT NULL DEFAULT '{}',
    error_class    VARCHAR(80),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_logs_created ON ai_call_logs(created_at DESC);
CREATE INDEX idx_ai_logs_capability ON ai_call_logs(capability, status, created_at DESC);

CREATE TABLE prompt_templates (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    capability    VARCHAR(32) NOT NULL,
    prompt_key    VARCHAR(80) NOT NULL,
    version       INT NOT NULL,
    template_text TEXT NOT NULL,
    output_schema JSONB,
    status        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (capability, prompt_key, version)
);

-- ---------------------------------------------------------------------
-- Admin audit
-- ---------------------------------------------------------------------
CREATE TABLE admin_audit_logs (
    id          BIGSERIAL PRIMARY KEY,
    actor_id    UUID NOT NULL REFERENCES users(id),
    action      VARCHAR(80) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id   VARCHAR(80) NOT NULL,
    before_json JSONB,
    after_json  JSONB,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_target ON admin_audit_logs(target_type, target_id, created_at DESC);

-- ---------------------------------------------------------------------
-- Async jobs / idempotency / events / content（规格书 §5.1 要求补充）
-- ---------------------------------------------------------------------
CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id),
    job_type        VARCHAR(40) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    progress        SMALLINT NOT NULL DEFAULT 0,
    result_ref      VARCHAR(255),
    result_json     JSONB NOT NULL DEFAULT '{}',
    error_json      JSONB,
    idempotency_key VARCHAR(120),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);
CREATE INDEX idx_jobs_user_created ON jobs(user_id, created_at DESC);
CREATE INDEX idx_jobs_status ON jobs(status, created_at DESC);

CREATE TABLE idempotency_records (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID REFERENCES users(id),
    path          VARCHAR(200) NOT NULL,
    idem_key      VARCHAR(120) NOT NULL,
    request_hash  VARCHAR(64) NOT NULL,
    response_json JSONB NOT NULL DEFAULT '{}',
    status_code   INT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, path, idem_key)
);

CREATE TABLE learning_events (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id),
    event_type   VARCHAR(60) NOT NULL,
    payload_json JSONB NOT NULL DEFAULT '{}',
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_events_user_time ON learning_events(user_id, occurred_at DESC);

CREATE TABLE content_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    item_type     VARCHAR(40) NOT NULL,
    title         VARCHAR(180) NOT NULL,
    difficulty    SMALLINT NOT NULL DEFAULT 3,
    duration_min  SMALLINT NOT NULL DEFAULT 10,
    tags          JSONB NOT NULL DEFAULT '[]',
    prerequisites JSONB NOT NULL DEFAULT '[]',
    content_json  JSONB NOT NULL DEFAULT '{}',
    status        VARCHAR(20) NOT NULL DEFAULT 'PUBLISHED',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_content_type ON content_items(item_type, status, difficulty);
