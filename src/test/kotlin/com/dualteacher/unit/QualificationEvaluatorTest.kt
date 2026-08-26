package com.dualteacher.unit

import com.dualteacher.domain.*
import com.dualteacher.service.QualificationEvaluator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class QualificationEvaluatorTest {

    private val evaluator = QualificationEvaluator()

    private fun cert(
        id: String = "C1",
        type: String = "EQUIPMENT",
        issued: String = "2025-01-01",
        expiry: String = "2026-06-30",
        revokedAt: String? = null,
        issueEventId: String = "evt-issue-$id",
        revokeEventId: String? = revokedAt?.let { "evt-revoke-$id" }
    ) = Certification(
        certificationId = id,
        teacherId = "T1",
        certType = type,
        issuedDate = LocalDate.parse(issued),
        expiryDate = LocalDate.parse(expiry),
        issuer = "Issuer",
        revokedAt = revokedAt?.let { LocalDate.parse(it) },
        issueEventId = issueEventId,
        revokeEventId = revokeEventId
    )

    private fun project(
        id: String = "P1",
        acceptedAt: String? = "2025-06-01",
        recordEventId: String = "evt-rec-$id",
        acceptEventId: String? = acceptedAt?.let { "evt-acc-$id" }
    ) = IndustryProject(
        projectId = id,
        teacherId = "T1",
        projectName = "Project $id",
        role = "Developer",
        startDate = null,
        endDate = null,
        company = "Company",
        acceptedAt = acceptedAt?.let { LocalDate.parse(it) },
        recordEventId = recordEventId,
        acceptEventId = acceptEventId
    )

    private fun practiceInterval(
        id: String,
        start: String,
        end: String,
        eventId: String = "evt-$id"
    ) = PracticeInterval(
        intervalId = id,
        teacherId = "T1",
        startDate = LocalDate.parse(start),
        endDate = LocalDate.parse(end),
        company = "Company",
        role = null,
        eventId = eventId
    )

    private fun matrix(
        version: Int = 1,
        effectiveDate: String = "2025-01-01",
        minPracticeDays: Int = 90,
        requiredCerts: List<String> = listOf("EQUIPMENT"),
        minProjects: Int = 1,
        eventId: String = "evt-matrix-$version"
    ) = CapabilityMatrix(
        matrixId = "M$version",
        courseId = "COURSE1",
        version = version,
        effectiveDate = LocalDate.parse(effectiveDate),
        requirements = MatrixRequirements(
            minPracticeDays = minPracticeDays,
            requiredCertTypes = requiredCerts,
            minAcceptedProjects = minProjects
        ),
        eventId = eventId
    )

    private fun state(
        practice: List<PracticeInterval> = listOf(practiceInterval("i1", "2025-01-01", "2025-04-01")),
        certs: List<Certification> = listOf(cert()),
        projects: List<IndustryProject> = listOf(project())
    ) = ProjectedState(
        teacher = Teacher("T1", "Teacher 1"),
        practiceIntervals = practice,
        certifications = certs,
        projects = projects,
        matrices = emptyList()
    )

    @Test
    fun `certification valid on issue date`() {
        val c = cert(issued = "2026-01-01", expiry = "2026-12-31")
        assertTrue(c.isValidOn(LocalDate.parse("2026-01-01")))
    }

    @Test
    fun `certification valid on expiry date`() {
        val c = cert(issued = "2026-01-01", expiry = "2026-06-30")
        assertTrue(c.isValidOn(LocalDate.parse("2026-06-30")))
    }

    @Test
    fun `certification invalid day after expiry`() {
        val c = cert(issued = "2026-01-01", expiry = "2026-06-30")
        assertFalse(c.isValidOn(LocalDate.parse("2026-07-01")))
    }

    @Test
    fun `certification invalid before issue date`() {
        val c = cert(issued = "2026-01-01", expiry = "2026-12-31")
        assertFalse(c.isValidOn(LocalDate.parse("2025-12-31")))
    }

    @Test
    fun `certification valid day before revocation`() {
        val c = cert(revokedAt = "2026-05-01")
        assertTrue(c.isValidOn(LocalDate.parse("2026-04-30")))
    }

    @Test
    fun `certification invalid on revocation date`() {
        val c = cert(revokedAt = "2026-05-01")
        assertFalse(c.isValidOn(LocalDate.parse("2026-05-01")))
    }

    @Test
    fun `project accepted on asOf counts`() {
        val p = project(acceptedAt = "2026-05-01")
        assertTrue(p.isAcceptedBy(LocalDate.parse("2026-05-01")))
    }

    @Test
    fun `project not accepted before acceptance date`() {
        val p = project(acceptedAt = "2026-05-01")
        assertFalse(p.isAcceptedBy(LocalDate.parse("2026-04-30")))
    }

    @Test
    fun `project without acceptance does not count`() {
        val p = project(acceptedAt = null)
        assertFalse(p.isAcceptedBy(LocalDate.parse("2026-12-31")))
    }

    @Test
    fun `latest matrix version is selected`() {
        val matrices = listOf(
            matrix(version = 1, effectiveDate = "2025-01-01", minPracticeDays = 30),
            matrix(version = 2, effectiveDate = "2026-01-01", minPracticeDays = 90)
        )
        val s = state()

        val result = evaluator.evaluate(s, matrices, "T1", "COURSE1", LocalDate.parse("2026-06-01"))
        val practiceItem = result.items.first { it.key == "practice" }
        assertEquals("≥ 90 天", practiceItem.required)
    }

    @Test
    fun `older matrix version is used when asOf is before newer version`() {
        val matrices = listOf(
            matrix(version = 1, effectiveDate = "2025-01-01", minPracticeDays = 30),
            matrix(version = 2, effectiveDate = "2026-01-01", minPracticeDays = 90)
        )
        val s = state(practice = listOf(practiceInterval("i1", "2025-01-01", "2025-02-01")))

        val result = evaluator.evaluate(s, matrices, "T1", "COURSE1", LocalDate.parse("2025-06-01"))
        val practiceItem = result.items.first { it.key == "practice" }
        assertEquals("≥ 30 天", practiceItem.required)
        assertEquals(QualificationStatus.PASS, practiceItem.status)
    }

    @Test
    fun `no matrix results in FAIL`() {
        val result = evaluator.evaluate(state(), emptyList(), "T1", "COURSE1", LocalDate.parse("2026-01-01"))
        assertEquals(QualificationStatus.FAIL, result.overallResult)
        assertEquals("matrix", result.items[0].key)
    }

    @Test
    fun `all requirements pass yields PASS`() {
        val matrices = listOf(matrix(minPracticeDays = 90))
        val result = evaluator.evaluate(state(), matrices, "T1", "COURSE1", LocalDate.parse("2026-04-30"))
        assertEquals(QualificationStatus.PASS, result.overallResult)
    }

    @Test
    fun `insufficient practice yields FAIL with missing info`() {
        val matrices = listOf(matrix(minPracticeDays = 200))
        val result = evaluator.evaluate(state(), matrices, "T1", "COURSE1", LocalDate.parse("2026-04-30"))
        val practiceItem = result.items.first { it.key == "practice" }
        assertEquals(QualificationStatus.FAIL, practiceItem.status)
        assertNotNull(practiceItem.missing)
        assertTrue(practiceItem.missing!!.contains("差"))
    }

    @Test
    fun `expired certification yields FAIL`() {
        val matrices = listOf(matrix())
        val s = state(certs = listOf(cert(expiry = "2026-04-01")))
        val result = evaluator.evaluate(s, matrices, "T1", "COURSE1", LocalDate.parse("2026-04-30"))
        val certItem = result.items.first { it.key == "certification:EQUIPMENT" }
        assertEquals(QualificationStatus.FAIL, certItem.status)
        assertTrue(certItem.actual.contains("过期"))
    }

    @Test
    fun `revoked certification yields FAIL`() {
        val matrices = listOf(matrix())
        val s = state(certs = listOf(cert(revokedAt = "2026-04-01")))
        val result = evaluator.evaluate(s, matrices, "T1", "COURSE1", LocalDate.parse("2026-04-30"))
        val certItem = result.items.first { it.key == "certification:EQUIPMENT" }
        assertEquals(QualificationStatus.FAIL, certItem.status)
        assertTrue(certItem.actual.contains("吊销"))
    }

    @Test
    fun `evidence ids are included in result`() {
        val matrices = listOf(matrix())
        val s = state()
        val result = evaluator.evaluate(s, matrices, "T1", "COURSE1", LocalDate.parse("2026-04-30"))

        val practiceItem = result.items.first { it.key == "practice" }
        assertTrue(practiceItem.evidenceIds.isNotEmpty())

        val certItem = result.items.first { it.key == "certification:EQUIPMENT" }
        assertTrue(certItem.evidenceIds.isNotEmpty())

        val projectItem = result.items.first { it.key == "projects" }
        assertTrue(projectItem.evidenceIds.isNotEmpty())
    }
}
