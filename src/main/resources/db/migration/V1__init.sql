CREATE TABLE events (
    event_id     VARCHAR(128) PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    occurred_at  DATE         NOT NULL,
    received_at  TIMESTAMP    NOT NULL DEFAULT now(),
    payload      JSONB        NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_events_aggregate_occurred ON events (aggregate_id, occurred_at, event_id);
CREATE INDEX idx_events_type_occurred     ON events (event_type, occurred_at);

CREATE TABLE decisions (
    decision_id    UUID         PRIMARY KEY,
    teacher_id     VARCHAR(128) NOT NULL,
    course_id      VARCHAR(128) NOT NULL,
    as_of          DATE         NOT NULL,
    overall_result VARCHAR(16)  NOT NULL,
    snapshot       JSONB        NOT NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_decision_scope UNIQUE (teacher_id, course_id, as_of)
);

CREATE INDEX idx_decisions_teacher ON decisions (teacher_id);
CREATE INDEX idx_decisions_course  ON decisions (course_id);
