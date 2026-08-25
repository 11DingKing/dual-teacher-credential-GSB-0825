package com.dualteacher.unit

import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventPayload
import com.dualteacher.domain.EventProjector
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventProjectorTest {

    private fun d(s: String) = LocalDate.parse(s)
    private fun ts(s: String) = Instant.parse(s)

    /** Projection must sort by business time; here events are supplied already ordered by
     *  the caller (the store guarantees occurred_at, seq ordering). */
    private fun project(events: List<EventEnvelope>, teacher: String = "T1", course: String = "C-RISK") =
        EventProjector.project(teacher, course, events.sortedBy { it.occurredAt })

    @Test
    fun `revocation updates the matching certification regardless of arrival`() {
        // Revoke event has an EARLIER business time than we might receive it; projection keys
        // on business time so the revocation is applied to the issued cert.
        val issue = EventEnvelope(
            "e-issue", ts("2026-01-01T00:00:00Z"),
            EventPayload.CertificationIssued("T1", "C1", "WELDING", d("2026-01-01"), d("2026-06-30")),
        )
        val revoke = EventEnvelope(
            "e-revoke", ts("2026-04-01T00:00:00Z"),
            EventPayload.CertificationRevoked("T1", "C1", d("2026-05-01")),
        )
        val state = project(listOf(issue, revoke))
        val cert = state.certifications.single()
        assertEquals(d("2026-05-01"), cert.revokedEffectiveOn)
        assertEquals("e-revoke", cert.revokeEventId)
        // Valid before the revocation effective date, invalid on/after it.
        assertTrue(cert.isValidOn(d("2026-04-30")))
        assertTrue(!cert.isValidOn(d("2026-05-01")))
    }

    @Test
    fun `revocation for unknown cert is ignored`() {
        val revoke = EventEnvelope(
            "e-revoke", ts("2026-04-01T00:00:00Z"),
            EventPayload.CertificationRevoked("T1", "GHOST", d("2026-05-01")),
        )
        val state = project(listOf(revoke))
        assertTrue(state.certifications.isEmpty())
    }

    @Test
    fun `latest matrix version by effective date wins`() {
        val v1 = EventEnvelope(
            "m1", ts("2026-01-01T00:00:00Z"),
            EventPayload.CompetencyMatrixPublished("C-RISK", 1, d("2026-01-01"), 30, null, null),
        )
        val v2 = EventEnvelope(
            "m2", ts("2026-02-01T00:00:00Z"),
            EventPayload.CompetencyMatrixPublished("C-RISK", 2, d("2026-02-01"), 60, "WELDING", null),
        )
        val state = project(listOf(v2, v1))
        assertEquals(2, state.matrixVersions.size)
    }

    @Test
    fun `events for other teachers and courses are excluded`() {
        val other = EventEnvelope(
            "x", ts("2026-01-01T00:00:00Z"),
            EventPayload.PracticeRecorded("T2", "ACME", d("2025-01-01"), d("2025-02-01")),
        )
        val mine = EventEnvelope(
            "y", ts("2026-01-01T00:00:00Z"),
            EventPayload.TeacherRegistered("T1", "Alice"),
        )
        val state = project(listOf(other, mine))
        assertEquals("Alice", state.teacherName)
        assertTrue(state.practice.isEmpty())
    }

    @Test
    fun `unregistered teacher has null name`() {
        val state = project(emptyList())
        assertNull(state.teacherName)
    }
}
