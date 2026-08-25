package com.dualteacher.integration

import com.dualteacher.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IdempotencyAndConcurrencyTest : IntegrationTestBase() {

    private fun buildEvent(
        eventId: String,
        type: EventType,
        aggregateId: String,
        occurredAt: String,
        payload: JsonObject
    ) = EventEnvelope(
        eventId = eventId,
        eventType = type,
        aggregateId = aggregateId,
        occurredAt = LocalDate.parse(occurredAt),
        payload = payload
    )

    @Test
    fun `duplicate event is idempotent - second append returns false`() {
        runBlocking {
            val payload = JsonObject(mapOf("name" to JsonPrimitive("Test Teacher")))
            val event = buildEvent("evt-dup-1", EventType.TEACHER_CREATED, "T-DUP", "2025-01-01", payload)

            val first = eventStore.append(event)
            val second = eventStore.append(event)

            assertTrue(first)
            assertFalse(second)
        }
    }

    @Test
    fun `same event retried does not duplicate in event stream`() {
        runBlocking {
            val payload = JsonObject(mapOf("name" to JsonPrimitive("Retry Teacher")))
            val event = buildEvent("evt-retry-1", EventType.TEACHER_CREATED, "T-RETRY", "2025-01-01", payload)

            eventStore.append(event)
            eventStore.append(event)
            eventStore.append(event)

            val events = eventStore.loadEventsForTeacher("T-RETRY", LocalDate.parse("2026-01-01"))
            assertEquals(1, events.size)
            assertEquals("evt-retry-1", events[0].eventId)
        }
    }

    @Test
    fun `concurrent requests for same decision create only one snapshot`() = runBlocking {
        val teacherId = "T-CONC"
        val courseId = "COURSE_CONC"
        val asOf = LocalDate.parse("2026-04-30")

        val teacherPayload = JsonObject(mapOf("name" to JsonPrimitive("Concurrent Teacher")))
        eventStore.append(buildEvent("evt-conc-t", EventType.TEACHER_CREATED, teacherId, "2025-01-01", teacherPayload))

        val practicePayload = JsonObject(mapOf(
            "intervalId" to JsonPrimitive("PI-CONC"),
            "startDate" to JsonPrimitive("2025-01-01"),
            "endDate" to JsonPrimitive("2025-06-01"),
            "company" to JsonPrimitive("Acme")
        ))
        eventStore.append(buildEvent("evt-conc-p", EventType.PRACTICE_RECORDED, teacherId, "2025-06-02", practicePayload))

        val matrixPayload = JsonObject(mapOf(
            "matrixId" to JsonPrimitive("M-CONC"),
            "courseId" to JsonPrimitive(courseId),
            "version" to JsonPrimitive(1),
            "requirements" to JsonObject(mapOf(
                "minPracticeDays" to JsonPrimitive(30),
                "requiredCertTypes" to kotlinx.serialization.json.JsonArray(emptyList()),
                "minAcceptedProjects" to JsonPrimitive(0)
            ))
        ))
        eventStore.append(buildEvent("evt-conc-m", EventType.MATRIX_PUBLISHED, courseId, "2025-01-01", matrixPayload))

        val results = withContext(Dispatchers.IO) {
            (1..10).map {
                async {
                    service.queryQualification(teacherId, courseId, asOf)
                }
            }.awaitAll()
        }

        val decisionIds = results.map { it.decisionId }.toSet()
        assertEquals(1, decisionIds.size, "Only one decision should be created despite concurrent requests")

        val allPass = results.all { it.overallResult == QualificationStatus.PASS }
        assertTrue(allPass)
    }

    @Test
    fun `old decision snapshot is immutable after new events arrive`() = runBlocking {
        val teacherId = "T-IMMUT"
        val courseId = "COURSE_IMMUT"
        val asOf = LocalDate.parse("2026-04-30")

        val teacherPayload = JsonObject(mapOf("name" to JsonPrimitive("Immutable Teacher")))
        eventStore.append(buildEvent("evt-imm-t", EventType.TEACHER_CREATED, teacherId, "2025-01-01", teacherPayload))

        val practicePayload = JsonObject(mapOf(
            "intervalId" to JsonPrimitive("PI-IMMUT"),
            "startDate" to JsonPrimitive("2025-01-01"),
            "endDate" to JsonPrimitive("2025-02-01"),
            "company" to JsonPrimitive("Acme")
        ))
        eventStore.append(buildEvent("evt-imm-p", EventType.PRACTICE_RECORDED, teacherId, "2025-02-02", practicePayload))

        val matrixV1Payload = JsonObject(mapOf(
            "matrixId" to JsonPrimitive("M-IMMUT-1"),
            "courseId" to JsonPrimitive(courseId),
            "version" to JsonPrimitive(1),
            "requirements" to JsonObject(mapOf(
                "minPracticeDays" to JsonPrimitive(100),
                "requiredCertTypes" to kotlinx.serialization.json.JsonArray(emptyList()),
                "minAcceptedProjects" to JsonPrimitive(0)
            ))
        ))
        eventStore.append(buildEvent("evt-imm-m1", EventType.MATRIX_PUBLISHED, courseId, "2025-01-01", matrixV1Payload))

        val originalDecision = service.queryQualification(teacherId, courseId, asOf)
        assertEquals(QualificationStatus.FAIL, originalDecision.overallResult)
        val originalSnapshotStr = Json.encodeToString(
            DecisionSnapshot.serializer(), originalDecision.snapshot
        )
        val originalDecisionId = originalDecision.decisionId

        val additionalPractice = JsonObject(mapOf(
            "intervalId" to JsonPrimitive("PI-IMMUT-2"),
            "startDate" to JsonPrimitive("2025-03-01"),
            "endDate" to JsonPrimitive("2025-09-01"),
            "company" to JsonPrimitive("Acme")
        ))
        eventStore.append(buildEvent("evt-imm-p2", EventType.PRACTICE_RECORDED, teacherId, "2025-09-02", additionalPractice))

        val fetchedDecision = service.getDecision(originalDecisionId)
        assertNotNull(fetchedDecision)

        val fetchedSnapshotStr = Json.encodeToString(
            DecisionSnapshot.serializer(), fetchedDecision!!.snapshot
        )

        assertEquals(originalSnapshotStr, fetchedSnapshotStr, "Snapshot must be immutable despite new events")
        assertEquals(QualificationStatus.FAIL, fetchedDecision.overallResult)
        assertEquals("32 天", fetchedDecision.snapshot.items.first { it.key == "practice" }.actual,
            "Snapshot should still show original 32 days, not updated count")

        val requery = service.queryQualification(teacherId, courseId, asOf)
        assertEquals(originalDecisionId, requery.decisionId, "Same scope should return the existing decision")
    }
}
