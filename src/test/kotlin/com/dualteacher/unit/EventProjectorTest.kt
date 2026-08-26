package com.dualteacher.unit

import com.dualteacher.domain.*
import com.dualteacher.persistence.StoredEvent
import com.dualteacher.service.EventProjector
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EventProjectorTest {

    private val projector = EventProjector()
    private val json = Json { ignoreUnknownKeys = true }

    private inline fun <reified T> event(
        eventId: String,
        type: EventType,
        aggregateId: String,
        occurredAt: String,
        payload: T
    ): StoredEvent {
        val element = json.encodeToJsonElement(kotlinx.serialization.serializer<T>(), payload)
        return StoredEvent(
            eventId = eventId,
            eventType = type,
            aggregateId = aggregateId,
            occurredAt = LocalDate.parse(occurredAt),
            payload = element
        )
    }

    @Test
    fun `out-of-order revocation is applied at effective date not arrival order`() {
        val revocation = event(
            "evt-revoke-1",
            EventType.CERTIFICATION_REVOKED,
            "T1",
            "2026-05-01",
            CertificationRevokedPayload("C1", "violation")
        )
        val issuance = event(
            "evt-issue-1",
            EventType.CERTIFICATION_ISSUED,
            "T1",
            "2025-01-01",
            CertificationIssuedPayload("C1", "EQUIPMENT", LocalDate.parse("2026-06-30"), "Issuer")
        )

        // Revocation arrives BEFORE issuance in the list (out of order by received time)
        val state = projector.projectTeacherEvents("T1", listOf(revocation, issuance))

        assertEquals(1, state.certifications.size)
        val cert = state.certifications[0]
        assertEquals("C1", cert.certificationId)
        assertEquals(LocalDate.parse("2026-05-01"), cert.revokedAt)
        assertNotNull(cert.revokeEventId)
    }

    @Test
    fun `events are projected by occurredAt regardless of input order`() {
        val practice2 = event(
            "evt-p2", EventType.PRACTICE_RECORDED, "T1", "2025-02-15",
            PracticeRecordedPayload("p2", LocalDate.parse("2025-02-15"), LocalDate.parse("2025-04-01"), "Co B")
        )
        val practice1 = event(
            "evt-p1", EventType.PRACTICE_RECORDED, "T1", "2025-01-01",
            PracticeRecordedPayload("p1", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-03-01"), "Co A")
        )

        val state = projector.projectTeacherEvents("T1", listOf(practice2, practice1))

        assertEquals(2, state.practiceIntervals.size)
        assertEquals("p1", state.practiceIntervals[0].intervalId)
        assertEquals("p2", state.practiceIntervals[1].intervalId)
    }

    @Test
    fun `project accepted after recording links correctly`() {
        val recorded = event(
            "evt-rec", EventType.PROJECT_RECORDED, "T1", "2025-01-01",
            ProjectRecordedPayload("P1", "Project Alpha", "Lead", null, null, "Co A")
        )
        val accepted = event(
            "evt-acc", EventType.PROJECT_ACCEPTED, "T1", "2025-06-01",
            ProjectAcceptedPayload("P1")
        )

        val state = projector.projectTeacherEvents("T1", listOf(recorded, accepted))

        assertEquals(1, state.projects.size)
        val p = state.projects[0]
        assertEquals(LocalDate.parse("2025-06-01"), p.acceptedAt)
        assertEquals("evt-acc", p.acceptEventId)
    }

    @Test
    fun `acceptance before recording is ignored`() {
        val accepted = event(
            "evt-acc", EventType.PROJECT_ACCEPTED, "T1", "2025-01-01",
            ProjectAcceptedPayload("P1")
        )
        val recorded = event(
            "evt-rec", EventType.PROJECT_RECORDED, "T1", "2025-06-01",
            ProjectRecordedPayload("P1", "Project Alpha", "Lead", null, null, "Co A")
        )

        val state = projector.projectTeacherEvents("T1", listOf(accepted, recorded))

        assertEquals(1, state.projects.size)
        assertNull(state.projects[0].acceptedAt)
        assertNull(state.projects[0].acceptEventId)
    }

    @Test
    fun `multiple matrix versions are all collected`() {
        val m1 = event(
            "evt-m1", EventType.MATRIX_PUBLISHED, "COURSE1", "2025-01-01",
            MatrixPublishedPayload("M1", "COURSE1", 1, MatrixRequirements(30, listOf("A"), 1))
        )
        val m2 = event(
            "evt-m2", EventType.MATRIX_PUBLISHED, "COURSE1", "2026-01-01",
            MatrixPublishedPayload("M2", "COURSE1", 2, MatrixRequirements(90, listOf("A", "B"), 2))
        )

        val state = projector.projectTeacherEvents("COURSE1", listOf(m2, m1))

        assertEquals(2, state.matrices.size)
        assertEquals(1, state.matrices[0].version)
        assertEquals(2, state.matrices[1].version)
    }

    @Test
    fun `teacher created event projects teacher`() {
        val e = event(
            "evt-t1", EventType.TEACHER_CREATED, "T1", "2025-01-01",
            TeacherCreatedPayload("张老师")
        )
        val state = projector.projectTeacherEvents("T1", listOf(e))

        assertNotNull(state.teacher)
        assertEquals("张老师", state.teacher!!.name)
    }

    @Test
    fun `certification revocation before issuance is safely ignored`() {
        val revocation = event(
            "evt-revoke", EventType.CERTIFICATION_REVOKED, "T1", "2025-01-01",
            CertificationRevokedPayload("C999")
        )

        val state = projector.projectTeacherEvents("T1", listOf(revocation))

        assertEquals(0, state.certifications.size)
    }

    @Test
    fun `tie-breaking by eventId for same occurredAt`() {
        val a = event("evt-a", EventType.PRACTICE_RECORDED, "T1", "2025-01-01",
            PracticeRecordedPayload("a", LocalDate.parse("2025-01-01"), LocalDate.parse("2025-01-01"), "A"))
        val b = event("evt-b", EventType.PRACTICE_RECORDED, "T1", "2025-01-01",
            PracticeRecordedPayload("b", LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-02"), "B"))

        val state1 = projector.projectTeacherEvents("T1", listOf(b, a))
        val state2 = projector.projectTeacherEvents("T1", listOf(a, b))

        assertEquals(state1.practiceIntervals.map { it.intervalId },
            state2.practiceIntervals.map { it.intervalId })
    }
}
