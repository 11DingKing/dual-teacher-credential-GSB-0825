package com.dualteacher.integration

import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventPayload
import com.dualteacher.domain.CheckStatus
import com.dualteacher.domain.QualificationEvaluator
import com.dualteacher.repo.DecisionRepository
import com.dualteacher.repo.EventStore
import com.dualteacher.service.QualificationService
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end T1 timeline against a real database:
 *  - two overlapping practice windows (merged to one span),
 *  - certification C1 issued through 2026-06-30,
 *  - a revocation that ARRIVES FIRST but is effective 2026-05-01.
 * Queries on 2026-04-30 / 2026-05-01 / 2026-07-01 must match the timeline.
 */
class T1QualificationScenarioTest : IntegrationTestBase() {

    private val course = "C-RISK"
    private lateinit var service: QualificationService

    private fun d(s: String) = LocalDate.parse(s)
    private fun ts(s: String) = Instant.parse(s)

    @BeforeEach
    fun setup() {
        cleanDatabase()
        service = QualificationService(EventStore(), DecisionRepository())
        setupT1Data()
    }

    /** Note the revocation is appended BEFORE the issuance to simulate out-of-order arrival. */
    private fun setupT1Data() {
        service.append(EventEnvelope("t1-reg", ts("2024-12-01T00:00:00Z"),
            EventPayload.TeacherRegistered("T1", "Teacher One")))

        // Matrix requires 60 practice days and a WELDING certification.
        service.append(EventEnvelope("t1-matrix", ts("2025-12-01T00:00:00Z"),
            EventPayload.CompetencyMatrixPublished(course, 1, d("2026-01-01"), 60, "WELDING", null)))

        // Revocation arrives first (out of order), effective 2026-05-01.
        service.append(EventEnvelope("t1-revoke", ts("2026-03-15T00:00:00Z"),
            EventPayload.CertificationRevoked("T1", "C1", d("2026-05-01"))))

        // Issuance arrives after the revocation.
        service.append(EventEnvelope("t1-issue", ts("2026-01-01T00:00:00Z"),
            EventPayload.CertificationIssued("T1", "C1", "WELDING", d("2026-01-01"), d("2026-06-30"))))

        // Two overlapping practice windows.
        service.append(EventEnvelope("t1-p1", ts("2025-03-05T00:00:00Z"),
            EventPayload.PracticeRecorded("T1", "ACME", d("2025-01-01"), d("2025-03-01"))))
        service.append(EventEnvelope("t1-p2", ts("2025-04-05T00:00:00Z"),
            EventPayload.PracticeRecorded("T1", "ACME", d("2025-02-15"), d("2025-04-01"))))
    }

    @Test
    fun `T1 query on 2026-04-30 should PASS - cert valid, practice merged over threshold`() {
        val decision = service.evaluate("T1", course, d("2026-04-30"))
        assertTrue(decision.qualified, "expected qualified on 2026-04-30")
        assertEquals(1, decision.matrixVersion)

        val practice = decision.checks.single { it.requirement == QualificationEvaluator.REQ_PRACTICE }
        assertEquals(CheckStatus.PASS, practice.status)
        // Both practice events are cited as evidence.
        assertTrue(practice.evidenceEventIds.containsAll(listOf("t1-p1", "t1-p2")))

        val cert = decision.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.PASS, cert.status)
        assertTrue(cert.evidenceEventIds.contains("t1-issue"))
    }

    @Test
    fun `T1 query on 2026-05-01 should FAIL - revocation effective on this date`() {
        val decision = service.evaluate("T1", course, d("2026-05-01"))
        assertFalse(decision.qualified, "expected not qualified on 2026-05-01")
        val cert = decision.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.FAIL, cert.status)
        assertTrue(cert.detail.contains("revoked"), "detail should mention revocation: ${cert.detail}")
        assertTrue(cert.evidenceEventIds.contains("t1-revoke"))
        assertTrue(cert.missing != null)
    }

    @Test
    fun `T1 query on 2026-07-01 should FAIL - certification expired and revoked`() {
        val decision = service.evaluate("T1", course, d("2026-07-01"))
        assertFalse(decision.qualified, "expected not qualified on 2026-07-01")
        val cert = decision.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.FAIL, cert.status)
    }

    @Test
    fun `each query creates an immutable decision snapshot with unique decision_id`() {
        val a = service.evaluate("T1", course, d("2026-04-30"))
        val b = service.evaluate("T1", course, d("2026-05-01"))
        assertNotEquals(a.decisionId, b.decisionId)
        // Retrievable by id and equal to what was returned.
        assertEquals(a, service.getDecision(a.decisionId))
        assertEquals(b, service.getDecision(b.decisionId))
    }

    @Test
    fun `evidence ids are present in snapshot items`() {
        val decision = service.evaluate("T1", course, d("2026-04-30"))
        val matrixCheck = decision.checks.single { it.requirement == QualificationEvaluator.REQ_MATRIX }
        assertEquals(listOf("t1-matrix"), matrixCheck.evidenceEventIds)
    }
}
