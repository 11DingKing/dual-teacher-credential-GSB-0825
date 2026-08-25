package com.dualteacher.unit

import com.dualteacher.domain.CertificationFact
import com.dualteacher.domain.CheckStatus
import com.dualteacher.domain.DateInterval
import com.dualteacher.domain.MatrixVersionFact
import com.dualteacher.domain.PracticeFact
import com.dualteacher.domain.ProjectRoleFact
import com.dualteacher.domain.ProjectedState
import com.dualteacher.domain.QualificationEvaluator
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualificationEvaluatorTest {

    private fun d(s: String) = LocalDate.parse(s)

    private fun matrix(
        version: Int = 1,
        effectiveFrom: String = "2026-01-01",
        minDays: Int = 30,
        certType: String? = "WELDING",
        projectDomain: String? = null,
    ) = MatrixVersionFact("C-RISK", version, d(effectiveFrom), minDays, certType, projectDomain, "m$version")

    private fun cert(revEffective: String? = null) = CertificationFact(
        certId = "C1", certType = "WELDING",
        issuedOn = d("2026-01-01"), expiresOn = d("2026-06-30"),
        issueEventId = "e-issue",
        revokedEffectiveOn = revEffective?.let { d(it) },
        revokeEventId = revEffective?.let { "e-revoke" },
    )

    private val practice = listOf(
        PracticeFact(DateInterval(d("2025-01-01"), d("2025-03-01")), "e-p1"),
        PracticeFact(DateInterval(d("2025-02-15"), d("2025-04-01")), "e-p2"),
    )

    @Test
    fun `T1 on 2026-04-30 passes - cert still valid before revocation`() {
        val state = ProjectedState("Alice", practice, listOf(cert("2026-05-01")), emptyList(), listOf(matrix()))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-04-30"), state)
        assertTrue(qualified)
        assertEquals(1, outcome.matrixVersion)
        val certCheck = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.PASS, certCheck.status)
        assertTrue(certCheck.evidenceEventIds.contains("e-issue"))
    }

    @Test
    fun `T1 on 2026-05-01 fails - revocation effective that day`() {
        val state = ProjectedState("Alice", practice, listOf(cert("2026-05-01")), emptyList(), listOf(matrix()))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-05-01"), state)
        assertFalse(qualified)
        val certCheck = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.FAIL, certCheck.status)
        assertTrue(certCheck.detail.contains("revoked"))
        // Evidence should cite both the issue and the revoke events.
        assertTrue(certCheck.evidenceEventIds.containsAll(listOf("e-issue", "e-revoke")))
    }

    @Test
    fun `T1 on 2026-07-01 fails - cert expired and revoked`() {
        val state = ProjectedState("Alice", practice, listOf(cert("2026-05-01")), emptyList(), listOf(matrix()))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-07-01"), state)
        assertFalse(qualified)
        val certCheck = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_CERTIFICATION }
        assertEquals(CheckStatus.FAIL, certCheck.status)
    }

    @Test
    fun `cert valid exactly on expiry date but invalid the day after`() {
        val state = ProjectedState("Alice", practice, listOf(cert(null)), emptyList(), listOf(matrix()))
        assertTrue(QualificationEvaluator.evaluate("C-RISK", d("2026-06-30"), state).first)
        assertFalse(QualificationEvaluator.evaluate("C-RISK", d("2026-07-01"), state).first)
    }

    @Test
    fun `cert invalid the day before issuance`() {
        val state = ProjectedState("Alice", practice, listOf(cert(null)), emptyList(), listOf(matrix()))
        assertFalse(QualificationEvaluator.evaluate("C-RISK", d("2025-12-31"), state).first)
    }

    @Test
    fun `no matrix effective yields fail with missing explanation`() {
        val state = ProjectedState("Alice", practice, listOf(cert(null)), emptyList(), listOf(matrix(effectiveFrom = "2026-01-01")))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2025-06-01"), state)
        assertFalse(qualified)
        assertEquals(null, outcome.matrixVersion)
        assertEquals(QualificationEvaluator.REQ_MATRIX, outcome.checks.single().requirement)
        assertTrue(outcome.checks.single().missing != null)
    }

    @Test
    fun `newer matrix version raises the practice bar and can fail`() {
        // v2 requires 200 days; T1's merged practice is only 91 days.
        val m1 = matrix(version = 1, effectiveFrom = "2026-01-01", minDays = 30)
        val m2 = matrix(version = 2, effectiveFrom = "2026-03-01", minDays = 200)
        val state = ProjectedState("Alice", practice, listOf(cert(null)), emptyList(), listOf(m1, m2))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-04-30"), state)
        assertFalse(qualified)
        assertEquals(2, outcome.matrixVersion)
        val pr = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_PRACTICE }
        assertEquals(CheckStatus.FAIL, pr.status)
    }

    @Test
    fun `older matrix version still applies before newer takes effect`() {
        val m1 = matrix(version = 1, effectiveFrom = "2026-01-01", minDays = 30)
        val m2 = matrix(version = 2, effectiveFrom = "2026-03-01", minDays = 200)
        val state = ProjectedState("Alice", practice, listOf(cert(null)), emptyList(), listOf(m1, m2))
        // as_of before v2 effective => v1 applies (30 days), passes.
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-02-15"), state)
        assertTrue(qualified)
        assertEquals(1, outcome.matrixVersion)
    }

    @Test
    fun `unaccepted industry project fails project requirement`() {
        val m = matrix(projectDomain = "AUTOMOTIVE")
        val role = ProjectRoleFact("P1", "lead", "AUTOMOTIVE", accepted = false, acceptedOn = null, eventId = "e-proj")
        val state = ProjectedState("Alice", practice, listOf(cert(null)), listOf(role), listOf(m))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-04-30"), state)
        assertFalse(qualified)
        val pj = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_PROJECT }
        assertEquals(CheckStatus.FAIL, pj.status)
    }

    @Test
    fun `accepted industry project passes and is cited as evidence`() {
        val m = matrix(projectDomain = "AUTOMOTIVE")
        val role = ProjectRoleFact("P1", "lead", "AUTOMOTIVE", accepted = true, acceptedOn = d("2026-01-15"), eventId = "e-proj")
        val state = ProjectedState("Alice", practice, listOf(cert(null)), listOf(role), listOf(m))
        val (qualified, outcome) = QualificationEvaluator.evaluate("C-RISK", d("2026-04-30"), state)
        assertTrue(qualified)
        val pj = outcome.checks.single { it.requirement == QualificationEvaluator.REQ_PROJECT }
        assertEquals(listOf("e-proj"), pj.evidenceEventIds)
    }

    @Test
    fun `project accepted after as_of does not count yet`() {
        val m = matrix(projectDomain = "AUTOMOTIVE")
        val role = ProjectRoleFact("P1", "lead", "AUTOMOTIVE", accepted = true, acceptedOn = d("2026-05-15"), eventId = "e-proj")
        val state = ProjectedState("Alice", practice, listOf(cert(null)), listOf(role), listOf(m))
        assertFalse(QualificationEvaluator.evaluate("C-RISK", d("2026-04-30"), state).first)
    }
}
