package com.dualteacher.db

import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** The append-only source-event log. */
object EventsTable : Table("events") {
    val seq: Column<Long> = long("seq").autoIncrement()
    val eventId: Column<String> = text("event_id").uniqueIndex()
    val eventType: Column<String> = text("event_type")
    val teacherId: Column<String?> = text("teacher_id").nullable()
    val courseId: Column<String?> = text("course_id").nullable()
    val occurredAt = timestampWithTimeZone("occurred_at")
    val receivedAt = timestampWithTimeZone("received_at")
    val payload: Column<String> = jsonb("payload")

    override val primaryKey = PrimaryKey(seq)
}

/** Immutable decision snapshots keyed by a deterministic decision id. */
object DecisionsTable : Table("decisions") {
    val decisionId: Column<String> = text("decision_id")
    val teacherId: Column<String> = text("teacher_id")
    val courseId: Column<String> = text("course_id")
    val asOf = date("as_of")
    val qualified: Column<Boolean> = bool("qualified")
    val matrixVersion: Column<Int?> = integer("matrix_version").nullable()
    val decidedAt = timestampWithTimeZone("decided_at")
    val snapshot: Column<String> = jsonb("snapshot")

    override val primaryKey = PrimaryKey(decisionId)

    init {
        uniqueIndex("uq_decision_key", teacherId, courseId, asOf)
    }
}
