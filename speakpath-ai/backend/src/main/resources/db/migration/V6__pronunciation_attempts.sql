-- ---------------------------------------------------------------------------
-- 跟读评测记录：同一条学习内容（句子/词）可多次评测，保留历史。
-- ---------------------------------------------------------------------------
CREATE TABLE pronunciation_attempts (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id),
    task_id          UUID NOT NULL REFERENCES learning_tasks(id),
    item_index       SMALLINT NOT NULL,
    ref_text         TEXT NOT NULL,
    result_json      JSONB NOT NULL DEFAULT '{}',
    suggestions_json JSONB NOT NULL DEFAULT '[]',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_pron_attempts_task ON pronunciation_attempts(task_id, item_index, created_at DESC);
