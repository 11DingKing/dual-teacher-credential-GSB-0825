package com.dualteacher.persistence

import com.dualteacher.config.dbQuery
import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import java.time.LocalDate

data class StoredEvent(
    val eventId: String,
    val eventType: EventType,
    val aggregateId: String,
    val occurredAt: LocalDate,
    val payload: JsonElement
)

class EventStore {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun append(event: EventEnvelope): Boolean = dbQuery {
        val existing = EventsTable.select {
            EventsTable.id eq event.eventId
        }.firstOrNull()

        if (existing != null) {
            return@dbQuery false
        }

        EventsTable.insert {
            it[id] = event.eventId
            it[eventType] = event.eventType.name
            it[aggregateId] = event.aggregateId
            it[occurredAt] = event.occurredAt
            it[payload] = event.payload
        }
        true
    }

    suspend fun exists(eventId: String): Boolean = dbQuery {
        EventsTable.select { EventsTable.id eq eventId }.any()
    }

    suspend fun loadEventsForTeacher(
        teacherId: String,
        asOf: LocalDate
    ): List<StoredEvent> = dbQuery {
        EventsTable.select {
            (EventsTable.aggregateId eq teacherId) and
                (EventsTable.occurredAt lessEq asOf)
        }.orderBy(EventsTable.occurredAt, SortOrder.ASC)
            .orderBy(EventsTable.id, SortOrder.ASC)
            .map { it.toStoredEvent() }
    }

    suspend fun loadEventsForCourse(
        courseId: String,
        asOf: LocalDate
    ): List<StoredEvent> = dbQuery {
        EventsTable.select {
            (EventsTable.aggregateId eq courseId) and
                (EventsTable.eventType eq EventType.MATRIX_PUBLISHED.name) and
                (EventsTable.occurredAt lessEq asOf)
        }.orderBy(EventsTable.occurredAt, SortOrder.ASC)
            .orderBy(EventsTable.id, SortOrder.ASC)
            .map { it.toStoredEvent() }
    }

    private fun ResultRow.toStoredEvent(): StoredEvent {
        return StoredEvent(
            eventId = this[EventsTable.id].value,
            eventType = EventType.valueOf(this[EventsTable.eventType]),
            aggregateId = this[EventsTable.aggregateId],
            occurredAt = this[EventsTable.occurredAt],
            payload = this[EventsTable.payload]
        )
    }
}
