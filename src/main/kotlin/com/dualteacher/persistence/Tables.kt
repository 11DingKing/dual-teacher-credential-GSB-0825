package com.dualteacher.persistence

import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant

object EventsTable : IdTable<String>("events") {
    override val id = varchar("event_id", 128).entityId()
    val eventType = varchar("event_type", 64)
    val aggregateId = varchar("aggregate_id", 128)
    val occurredAt = date("occurred_at")
    val receivedAt = timestamp("received_at").default(Instant.now())
    val payload = jsonb<JsonElement>("payload", Json.Default)
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)
}

object DecisionsTable : IdTable<java.util.UUID>("decisions") {
    override val id = uuid("decision_id").entityId()
    val teacherId = varchar("teacher_id", 128)
    val courseId = varchar("course_id", 128)
    val asOf = date("as_of")
    val overallResult = varchar("overall_result", 16)
    val snapshot = jsonb<JsonElement>("snapshot", Json.Default)
    val createdAt = timestamp("created_at").default(Instant.now())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_decision_scope", teacherId, courseId, asOf)
    }
}
