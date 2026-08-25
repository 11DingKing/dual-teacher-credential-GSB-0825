package com.dualteacher.repo

import com.dualteacher.app.AppJson
import com.dualteacher.db.EventsTable
import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventPayload
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Persists source events and replays them in business-time order.
 *
 * Ingest is idempotent on [EventEnvelope.eventId]: re-appending a known id is a no-op. This
 * makes upstream retries safe regardless of arrival order.
 */
class EventStore {

    /** @return true if the event was newly stored, false if it was a duplicate (idempotent). */
    fun append(env: EventEnvelope): Boolean = transaction {
        val (teacherId, courseId) = keysOf(env.payload)
        try {
            EventsTable.insert {
                it[eventId] = env.eventId
                it[eventType] = env.payload::class.simpleName ?: "Unknown"
                it[EventsTable.teacherId] = teacherId
                it[EventsTable.courseId] = courseId
                it[occurredAt] = OffsetDateTime.ofInstant(env.occurredAt, ZoneOffset.UTC)
                it[receivedAt] = OffsetDateTime.now(ZoneOffset.UTC)
                it[payload] = AppJson.encodeToString(EventPayload.serializer(), env.payload)
            }
            true
        } catch (e: ExposedSQLException) {
            // Unique violation on event_id => already ingested. Treat as success (idempotent).
            if (isUniqueViolation(e)) false else throw e
        }
    }

    /** Loads all events relevant to a teacher and course, ordered by business time then seq. */
    fun loadFor(teacherId: String, courseId: String): List<EventEnvelope> = transaction {
        EventsTable.selectAll()
            .where {
                (EventsTable.teacherId eq teacherId) or (EventsTable.courseId eq courseId)
            }
            .orderBy(EventsTable.occurredAt to SortOrder.ASC, EventsTable.seq to SortOrder.ASC)
            .map { row ->
                EventEnvelope(
                    eventId = row[EventsTable.eventId],
                    occurredAt = row[EventsTable.occurredAt].toInstant(),
                    payload = AppJson.decodeFromString(EventPayload.serializer(), row[EventsTable.payload]),
                )
            }
    }

    private fun keysOf(payload: EventPayload): Pair<String?, String?> = when (payload) {
        is EventPayload.TeacherRegistered -> payload.teacherId to null
        is EventPayload.PracticeRecorded -> payload.teacherId to null
        is EventPayload.CertificationIssued -> payload.teacherId to null
        is EventPayload.CertificationRevoked -> payload.teacherId to null
        is EventPayload.IndustryProjectRoleRecorded -> payload.teacherId to null
        is EventPayload.CompetencyMatrixPublished -> null to payload.courseId
    }

    private fun isUniqueViolation(e: ExposedSQLException): Boolean {
        // PostgreSQL unique_violation SQLSTATE is 23505.
        return e.sqlState == "23505" || (e.cause as? java.sql.SQLException)?.sqlState == "23505"
    }
}
