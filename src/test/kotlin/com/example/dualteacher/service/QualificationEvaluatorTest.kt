package com.example.dualteacher.service

import com.example.dualteacher.domain.DecisionItem
import com.example.dualteacher.domain.Requirements
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualificationEvaluatorTest {

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    private fun item(items: List<DecisionItem>, type: String, subject: String): DecisionItem =
        items.single { it.type == type && it.subject == subject }

    // ---------- 企业实践：区间合并与日期边界 ----------

    @Test
    fun `practice exactly at required days passes`() {
        val practices = listOf(Practice(UUID.randomUUID(), d("2025-01-01"), d("2025-03-31"))) // 90 天
        val items = QualificationEvaluator.evaluate(
            Requirements(minPracticeDays = 90), practices, emptyList(), emptyList(), emptyList(), d("2026-01-01")
        )
        assertEquals("PASS", item(items, "practice", "enterprise_practice").status)
        assertEquals("90 days", item(items, "practice", "enterprise_practice").actual)
    }

    @Test
    fun `practice one day short fails with missing explanation`() {
        val practices = listOf(Practice(UUID.randomUUID(), d("2025-01-01"), d("2025-03-30"))) // 89 天
        val items = QualificationEvaluator.evaluate(
            Requirements(minPracticeDays = 90), practices, emptyList(), emptyList(), emptyList(), d("2026-01-01")
        )
        val practice = item(items, "practice", "enterprise_practice")
        assertEquals("FAIL", practice.status)
        assertEquals("PRACTICE_DAYS_INSUFFICIENT", practice.reasonCode)
        assertTrue(practice.missing!!.contains("89"))
    }

    @Test
    fun `practice interval is clipped at as_of`() {
        val practices = listOf(Practice(UUID.randomUUID(), d("2025-01-01"), d("2025-12-31")))
        val items = QualificationEvaluator.evaluate(
            Requirements(minPracticeDays = 91), practices, emptyList(), emptyList(), emptyList(), d("2025-04-01")
        )
        // 只累计到 as_of：2025-01-01..2025-04-01 = 91 天
        assertEquals("PASS", item(items, "practice", "enterprise_practice").status)
    }

    @Test
    fun `practice interval starting after as_of is ignored`() {
        val practices = listOf(Practice(UUID.randomUUID(), d("2026-01-01"), d("2026-06-30")))
        val items = QualificationEvaluator.evaluate(
            Requirements(minPracticeDays = 1), practices, emptyList(), emptyList(), emptyList(), d("2025-12-31")
        )
        val practice = item(items, "practice", "enterprise_practice")
        assertEquals("FAIL", practice.status)
        assertEquals("0 days", practice.actual)
        assertEquals(emptyList(), practice.evidenceIds)
    }

    // ---------- 认证：签发/到期/吊销边界（含乱序吊销） ----------

    private val certId = UUID.randomUUID()
    private val revocationId = UUID.randomUUID()
    private val c1 = Cert(certId, "C1", issued = d("2025-06-01"), expiry = d("2026-06-30"))
    private val revocation = Revocation(revocationId, "C1", effective = d("2026-05-01"))

    @Test
    fun `certification valid on its expiry date`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), listOf(c1), emptyList(), emptyList(), d("2026-06-30")
        )
        assertEquals("PASS", item(items, "certification", "C1").status)
    }

    @Test
    fun `certification invalid the day after expiry`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), listOf(c1), emptyList(), emptyList(), d("2026-07-01")
        )
        val cert = item(items, "certification", "C1")
        assertEquals("FAIL", cert.status)
        assertEquals("CERT_EXPIRED", cert.reasonCode)
        assertTrue(cert.evidenceIds.contains(certId.toString()))
    }

    @Test
    fun `revocation not yet effective on 2026-04-30 so certification passes`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), listOf(c1), listOf(revocation), emptyList(), d("2026-04-30")
        )
        assertEquals("PASS", item(items, "certification", "C1").status)
    }

    @Test
    fun `revocation effective on 2026-05-01 fails certification with revocation evidence`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), listOf(c1), listOf(revocation), emptyList(), d("2026-05-01")
        )
        val cert = item(items, "certification", "C1")
        assertEquals("FAIL", cert.status)
        assertEquals("CERT_REVOKED", cert.reasonCode)
        assertTrue(cert.evidenceIds.contains(revocationId.toString()))
        assertTrue(cert.evidenceIds.contains(certId.toString()))
    }

    @Test
    fun `certification before its issue date is not issued yet`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), listOf(c1), emptyList(), emptyList(), d("2025-05-31")
        )
        assertEquals("CERT_NOT_ISSUED", item(items, "certification", "C1").reasonCode)
    }

    @Test
    fun `certification never issued fails`() {
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredCertifications = listOf("C1")),
            emptyList(), emptyList(), emptyList(), emptyList(), d("2026-01-01")
        )
        assertEquals("CERT_NOT_ISSUED", item(items, "certification", "C1").reasonCode)
    }

    // ---------- 产业项目：必须经过企业验收 ----------

    @Test
    fun `accepted project passes`() {
        val project = Project(UUID.randomUUID(), "MENTOR", accepted = true, acceptanceDate = d("2025-09-01"))
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredProjectRoles = listOf("MENTOR")),
            emptyList(), emptyList(), emptyList(), listOf(project), d("2025-10-01")
        )
        assertEquals("PASS", item(items, "project_role", "MENTOR").status)
    }

    @Test
    fun `unaccepted project is not evidence`() {
        val project = Project(UUID.randomUUID(), "MENTOR", accepted = false, acceptanceDate = null)
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredProjectRoles = listOf("MENTOR")),
            emptyList(), emptyList(), emptyList(), listOf(project), d("2025-10-01")
        )
        val role = item(items, "project_role", "MENTOR")
        assertEquals("FAIL", role.status)
        assertEquals("PROJECT_NOT_ACCEPTED", role.reasonCode)
    }

    @Test
    fun `project accepted after as_of is not yet evidence`() {
        val project = Project(UUID.randomUUID(), "MENTOR", accepted = true, acceptanceDate = d("2025-09-01"))
        val items = QualificationEvaluator.evaluate(
            Requirements(requiredProjectRoles = listOf("MENTOR")),
            emptyList(), emptyList(), emptyList(), listOf(project), d("2025-08-31")
        )
        assertEquals("FAIL", item(items, "project_role", "MENTOR").status)
    }

    // ---------- 综合：T1 三个关键日期 ----------

    @Test
    fun `T1 timeline across 2026-04-30, 2026-05-01 and 2026-07-01`() {
        val requirements = Requirements(
            minPracticeDays = 90,
            requiredCertifications = listOf("C1"),
            requiredProjectRoles = listOf("MENTOR"),
        )
        val practices = listOf(
            Practice(UUID.randomUUID(), d("2025-01-01"), d("2025-03-01")),
            Practice(UUID.randomUUID(), d("2025-02-15"), d("2025-04-01")),
        )
        val projects = listOf(Project(UUID.randomUUID(), "MENTOR", accepted = true, acceptanceDate = d("2025-09-01")))

        fun qualifiedOn(asOf: String) = QualificationEvaluator.evaluate(
            requirements, practices, listOf(c1), listOf(revocation), projects, d(asOf)
        )

        val apr30 = qualifiedOn("2026-04-30")
        assertTrue(apr30.all { it.status == "PASS" })
        assertEquals("91 days", item(apr30, "practice", "enterprise_practice").actual)

        val may1 = qualifiedOn("2026-05-01")
        assertEquals("CERT_REVOKED", item(may1, "certification", "C1").reasonCode)
        assertTrue(may1.any { it.status == "FAIL" })

        val jul1 = qualifiedOn("2026-07-01")
        assertEquals("CERT_EXPIRED", item(jul1, "certification", "C1").reasonCode)
    }
}
