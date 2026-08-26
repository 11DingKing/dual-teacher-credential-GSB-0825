-- “双师型”教师资历服务初始结构
-- 事件日志表：所有来源事件以 event_id 全局唯一、幂等写入，
-- 资格按 occurred_at（业务发生时间）投影，与接收顺序无关。
CREATE TABLE events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(64) NOT NULL,
    teacher_id   VARCHAR(64),
    occurred_at  TIMESTAMP   NOT NULL,
    payload      TEXT        NOT NULL,
    received_at  TIMESTAMP   NOT NULL,
    created_at   TIMESTAMP   NOT NULL
);

CREATE INDEX idx_events_teacher   ON events (teacher_id);
CREATE INDEX idx_events_type_time ON events (event_type, occurred_at);

-- 资格决定快照表：同一 (教师, 课程, 基准日期) 只允许有一份不可变快照。
-- 补录材料不会改变旧决定：唯一约束保证并发/重复查询返回同一份快照。
CREATE TABLE decisions (
    decision_id    UUID        PRIMARY KEY,
    teacher_id     VARCHAR(64) NOT NULL,
    course_code    VARCHAR(64) NOT NULL,
    as_of_date     DATE        NOT NULL,
    matrix_version VARCHAR(32),
    result         VARCHAR(32) NOT NULL,
    snapshot_json  TEXT        NOT NULL,
    created_at     TIMESTAMP   NOT NULL,
    CONSTRAINT uq_decisions_teacher_course_asof
        UNIQUE (teacher_id, course_code, as_of_date)
);

CREATE INDEX idx_decisions_teacher ON decisions (teacher_id);
