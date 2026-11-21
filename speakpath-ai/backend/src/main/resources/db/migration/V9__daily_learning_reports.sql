-- 每日学习评价：每天 0 点后自动生成前一天的学习结果分析
CREATE TABLE daily_learning_reports (
    id   UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    report_date DATE NOT NULL,
    tasks_total INT NOT NULL DEFAULT 0,
    tasks_done INT NOT NULL DEFAULT 0,
    speaking_sessions INT NOT NULL DEFAULT 0,
    speaking_minutes INT NOT NULL DEFAULT 0,
    turns_count INT NOT NULL DEFAULT 0,
    pronunciation_avg INT,
    score INT,
    summary TEXT,
    suggestions JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_daily_learning_reports_user_date UNIQUE (user_id, report_date)
);

CREATE INDEX idx_daily_learning_reports_user_date
    ON daily_learning_reports (user_id, report_date DESC);
