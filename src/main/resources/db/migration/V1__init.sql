-- Source events, the immutable log of facts from upstream departments.
-- event_id is the global idempotency key; occurred_at is business time; seq is a stable
-- append tiebreaker so equal business times project deterministically.
CREATE TABLE IF NOT EXISTS events (
    seq          BIGSERIAL PRIMARY KEY,
    event_id     TEXT        NOT NULL UNIQUE,
    event_type   TEXT        NOT NULL,
    teacher_id   TEXT,
    course_id    TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    payload      JSONB       NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_teacher ON events (teacher_id);
CREATE INDEX IF NOT EXISTS idx_events_course ON events (course_id);
CREATE INDEX IF NOT EXISTS idx_events_order ON events (occurred_at, seq);

-- Immutable decision snapshots. decision_id is deterministic per (teacher, course, as_of):
-- a UNIQUE constraint lets concurrent creators race to a single stored row, and the row is
-- never updated afterwards, so backfilled events cannot rewrite past decisions.
CREATE TABLE IF NOT EXISTS decisions (
    decision_id   TEXT        PRIMARY KEY,
    teacher_id    TEXT        NOT NULL,
    course_id     TEXT        NOT NULL,
    as_of         DATE        NOT NULL,
    qualified     BOOLEAN     NOT NULL,
    matrix_version INTEGER,
    decided_at    TIMESTAMPTZ NOT NULL,
    snapshot      JSONB       NOT NULL,
    CONSTRAINT uq_decision_key UNIQUE (teacher_id, course_id, as_of)
);
