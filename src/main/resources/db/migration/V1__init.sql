-- 双师型教师资历服务 - 初始 schema
-- 设计：事件溯源。所有来源事件写入 events（event_id 全局唯一），
-- 同一事务内投影到业务表；业务表只携带业务时间，与到达顺序无关。

CREATE TABLE events (
    event_id     TEXT PRIMARY KEY,
    event_type   TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ,                 -- 业务发生时间（可选元数据）
    payload      JSONB       NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL          -- 服务器接收时间，仅用于审计
);

CREATE TABLE teachers (
    teacher_id TEXT PRIMARY KEY,
    name       TEXT        NOT NULL,
    event_id   TEXT        NOT NULL REFERENCES events (event_id),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE enterprise_practices (
    id         UUID PRIMARY KEY,
    teacher_id TEXT NOT NULL REFERENCES teachers (teacher_id),
    company    TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date   DATE NOT NULL,
    event_id   TEXT NOT NULL UNIQUE REFERENCES events (event_id),
    CONSTRAINT practice_dates_valid CHECK (end_date >= start_date)
);

CREATE TABLE certifications (
    id          UUID PRIMARY KEY,
    teacher_id  TEXT NOT NULL REFERENCES teachers (teacher_id),
    cert_code   TEXT NOT NULL,
    issued_date DATE NOT NULL,
    expiry_date DATE,
    event_id    TEXT NOT NULL UNIQUE REFERENCES events (event_id)
);

-- 吊销与签发解耦（只按 cert_code 关联），因此吊销可以先于签发到达
CREATE TABLE certification_revocations (
    id             UUID PRIMARY KEY,
    cert_code      TEXT NOT NULL,
    effective_date DATE NOT NULL,              -- 吊销的业务生效日期
    reason         TEXT,
    event_id       TEXT NOT NULL UNIQUE REFERENCES events (event_id)
);

CREATE TABLE industry_projects (
    id                     UUID PRIMARY KEY,
    teacher_id             TEXT NOT NULL REFERENCES teachers (teacher_id),
    project_name           TEXT    NOT NULL,
    role                   TEXT    NOT NULL,
    accepted_by_enterprise BOOLEAN NOT NULL DEFAULT FALSE,
    acceptance_date        DATE,
    event_id               TEXT    NOT NULL UNIQUE REFERENCES events (event_id)
);

CREATE TABLE course_matrices (
    id             UUID PRIMARY KEY,
    course_id      TEXT    NOT NULL,
    version        INTEGER NOT NULL,
    effective_from DATE    NOT NULL,           -- 该版本从何时起生效
    requirements   JSONB   NOT NULL,
    event_id       TEXT    NOT NULL UNIQUE REFERENCES events (event_id),
    CONSTRAINT course_matrices_course_version UNIQUE (course_id, version)
);

-- 资格判定快照：一旦写入不可变，补录材料不回溯修改
CREATE TABLE decisions (
    decision_id    UUID PRIMARY KEY,
    teacher_id     TEXT        NOT NULL REFERENCES teachers (teacher_id),
    course_id      TEXT        NOT NULL,
    as_of          DATE        NOT NULL,
    matrix_version INTEGER     NOT NULL,
    qualified      BOOLEAN     NOT NULL,
    items          JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT decisions_unique_key UNIQUE (teacher_id, course_id, as_of)
);

CREATE INDEX idx_practices_teacher ON enterprise_practices (teacher_id);
CREATE INDEX idx_certifications_teacher ON certifications (teacher_id);
CREATE INDEX idx_certifications_code ON certifications (cert_code);
CREATE INDEX idx_revocations_code ON certification_revocations (cert_code);
CREATE INDEX idx_projects_teacher ON industry_projects (teacher_id);
CREATE INDEX idx_matrices_course ON course_matrices (course_id, effective_from);
