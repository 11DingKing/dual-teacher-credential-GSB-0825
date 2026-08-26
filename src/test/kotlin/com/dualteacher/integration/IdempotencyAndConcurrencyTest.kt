package com.dualteacher.integration

import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventPayload
import com.dualteacher.repo.DecisionRepository
import com.dualteacher.repo.EventStore
import com.dualteacher.service.QualificationService
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdempotencyAndConcurrencyTest : IntegrationTestBase() {

    private val course = "C-RISK"
    private lateinit var events: EventStore
    private lateinit var decisions: DecisionRepository
    private lateinit var service: QualificationService

    private fun d(s: String) = LocalDate.parse(s)
    private fun ts(s: String) = Instant.parse(s)

    @BeforeEach
    fun setup() {
        cleanDatabase()
        events = EventStore()
        decisions = DecisionRepository()
        service = QualificationService(events, decisions)
    }

    @Test
    fun `duplicate event is idempotent - second append returns false`() {
        val env = EventEnvelope("dup-1", ts("2026-01-01T00:00:00Z"),
            EventPayload.TeacherRegistered("T1", "Teacher One"))
        assertTrue(events.append(env), "first append should store")
        assertFalse(events.append(env), "second append with same event_id should be ignored")

        // Even a payload change under the same id must not create a second row.
        val tampered = EventEnvelope("dup-1", ts("2026-01-01T00:00:00Z"),
            EventPayload.TeacherRegistered("T1", "Different Name"))
        assertFalse(events.append(tampered))

        val loaded = events.loadFor("T1", course)
        assertEquals(1, loaded.size)
        assertEquals("Teacher One", (loaded.single().payload as EventPayload.TeacherRegistered).name)
    }

    @Test
    fun `concurrent requests for same decision create only one snapshot`() {
        // Seed enough state for a deterministic decision.
        service.append(EventEnvelope("c-matrix", ts("2025-12-01T00:00:00Z"),
            EventPayload.CompetencyMatrixPublished(course, 1, d("2026-01-01"), 10, null, null)))
        service.append(EventEnvelope("c-p1", ts("2025-03-05T00:00:00Z"),
            EventPayload.PracticeRecorded("T1", "ACME", d("2025-01-01"), d("2025-03-01"))))

        val asOf = d("2026-04-30")
        val threads = 16
        val startGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        val results = ConcurrentLinkedQueue<String>()
        repeat(threads) {
            pool.submit {
                startGate.await()
                results.add(service.evaluate("T1", course, asOf).decisionId)
            }
        }
        startGate.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "workers did not finish")

        // All threads observe exactly one decision id.
        assertEquals(threads, results.size)
        assertEquals(1, results.toSet().size, "expected a single decision id across concurrent creators")
    }

    @Test
    fun `backfilling material does not change an existing decision`() {
        service.append(EventEnvelope("b-matrix", ts("2025-12-01T00:00:00Z"),
            EventPayload.CompetencyMatrixPublished(course, 1, d("2026-01-01"), 10, "WELDING", null)))
        service.append(EventEnvelope("b-p1", ts("2025-03-05T00:00:00Z"),
            EventPayload.PracticeRecorded("T1", "ACME", d("2025-01-01"), d("2025-03-01"))))

        // First query: no valid cert => not qualified. Snapshot is recorded.
        val first = service.evaluate("T1", course, d("2026-04-30"))
        assertFalse(first.qualified)

        // Backfill a certification that WOULD have satisfied the requirement.
        service.append(EventEnvelope("b-issue", ts("2026-01-01T00:00:00Z"),
            EventPayload.CertificationIssued("T1", "C1", "WELDING", d("2026-01-01"), d("2026-06-30"))))

        // Re-querying the same coordinate returns the ORIGINAL immutable decision.
        val second = service.evaluate("T1", course, d("2026-04-30"))
        assertEquals(first, second)
        assertFalse(second.qualified, "old decision must not flip after backfill")
    }
}
