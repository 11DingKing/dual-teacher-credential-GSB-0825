package com.gsb.dualteacher.domain

import com.gsb.dualteacher.Fixtures
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QualificationEvaluatorTest {

    private fun d(s: String) = LocalDate.parse(s)

    private fun eval(events: List<StoredEvent>, teacher: String, course: String, asOf: String) =
        QualificationEvaluator.evaluate(
            QualificationEvaluator.project(events, d(asOf)),
            teacher,
            course,
            d(asOf),
        )

    private fun QualificationEvaluator.Evaluation.item(code: String): DecisionItem =
        items.first { it.code == code }

    private fun QualificationEvaluator.Evaluation.certItem(): DecisionItem =
        items.first { it.code.startsWith("CERT_") }

    // ---------- T1 时间线 ----------

    @Test
    fun `2026-04-30 吊销尚未生效且证书未到期，T1 具备资格`() {
        val result = eval(Fixtures.t1Timeline(), Fixtures.T1, Fixtures.COURSE, "2026-04-30")

        assertEquals(QualificationResult.QUALIFIED, result.result)
        assertEquals("v1", result.matrixVersion)

        val practice = result.item("PRACTICE")
        assertEquals(ItemStatus.PASS, practice.status)
        assertTrue(practice.message.contains("91 天"), practice.message)
        // 两段重叠实践的事件 ID 都作为证据
        assertTrue(practice.evidenceIds.contains(Fixtures.ID_PRACTICE_1.toString()))
        assertTrue(practice.evidenceIds.contains(Fixtures.ID_PRACTICE_2.toString()))

        val cert = result.certItem()
        assertEquals(ItemStatus.PASS, cert.status)
        assertEquals(listOf(Fixtures.ID_CERT_ISSUED.toString()), cert.evidenceIds)

        val project = result.item("PROJECT")
        assertEquals(ItemStatus.PASS, project.status)
        assertTrue(project.evidenceIds.contains(Fixtures.ID_PROJECT_ASSIGNED.toString()))
        assertTrue(project.evidenceIds.contains(Fixtures.ID_PROJECT_ACCEPTED.toString()))
    }

    @Test
    fun `2026-05-01 吊销当天生效，T1 不具备资格且证据指向吊销事件`() {
        val result = eval(Fixtures.t1Timeline(), Fixtures.T1, Fixtures.COURSE, "2026-05-01")

        assertEquals(QualificationResult.NOT_QUALIFIED, result.result)
        val cert = result.certItem()
        assertEquals(ItemStatus.FAIL, cert.status)
        assertTrue(cert.message.contains("吊销"), cert.message)
        assertTrue(cert.evidenceIds.contains(Fixtures.ID_CERT_ISSUED.toString()))
        assertTrue(cert.evidenceIds.contains(Fixtures.ID_CERT_REVOKED.toString()))
        assertNotNull(cert.missing)
        // 实践与项目仍然通过
        assertEquals(ItemStatus.PASS, result.item("PRACTICE").status)
        assertEquals(ItemStatus.PASS, result.item("PROJECT").status)
    }

    @Test
    fun `2026-07-01 证书已过期，T1 不具备资格`() {
        val result = eval(Fixtures.t1Timeline(), Fixtures.T1, Fixtures.COURSE, "2026-07-01")

        assertEquals(QualificationResult.NOT_QUALIFIED, result.result)
        val cert = result.certItem()
        assertEquals(ItemStatus.FAIL, cert.status)
        // 吊销先生效（2026-05-01），证据同时包含签发与吊销
        assertTrue(cert.evidenceIds.contains(Fixtures.ID_CERT_REVOKED.toString()))
    }

    // ---------- 日期边界 ----------

    @Test
    fun `证书到期日当天仍有效，次日失效`() {
        val events = listOf(
            Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T2"),
            Fixtures.certIssued(
                id = UUID.randomUUID(), at = "2025-01-01", teacher = "T2",
                certId = "C2", issuedOn = "2025-01-01", expiresOn = "2025-03-01",
            ),
            Fixtures.matrix(id = UUID.randomUUID(), at = "2024-12-01", version = "v1",
                course = "C-BOUNDARY", effectiveFrom = "2025-01-01", practiceDays = 0,
                certTypes = listOf(Fixtures.CERT_TYPE), projects = 0),
        )
        assertEquals(
            ItemStatus.PASS,
            eval(events, "T2", "C-BOUNDARY", "2025-03-01").certItem().status,
        )
        assertEquals(
            ItemStatus.FAIL,
            eval(events, "T2", "C-BOUNDARY", "2025-03-02").certItem().status,
        )
    }

    @Test
    fun `吊销生效日前一天证书有效，生效日当天失效`() {
        val events = listOf(
            Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T3"),
            Fixtures.certIssued(
                id = UUID.randomUUID(), at = "2025-01-01", teacher = "T3",
                certId = "C3", issuedOn = "2025-01-01", expiresOn = "2027-12-31",
            ),
            Fixtures.certRevoked(
                id = UUID.randomUUID(), at = "2025-05-15", teacher = "T3",
                certId = "C3", effectiveOn = "2025-06-01",
            ),
            Fixtures.matrix(id = UUID.randomUUID(), at = "2024-12-01", version = "v1",
                course = "C-REVOKE", effectiveFrom = "2025-01-01", practiceDays = 0,
                certTypes = listOf(Fixtures.CERT_TYPE), projects = 0),
        )
        val before = eval(events, "T3", "C-REVOKE", "2025-05-31")
        val onDay = eval(events, "T3", "C-REVOKE", "2025-06-01")
        assertEquals(ItemStatus.PASS, before.certItem().status)
        assertEquals(ItemStatus.FAIL, onDay.certItem().status)
        assertTrue(onDay.certItem().message.contains("2025-06-01"))
    }

    @Test
    fun `业务时间在 asOf 之后的吊销事件不影响当日判断`() {
        // 吊销事件业务发生于 2026-05-02（次日才签发），即使它已经在事件流里，
        // 2026-05-01 的投影也不应看到它。
        val events = listOf(
            Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T4"),
            Fixtures.certIssued(
                id = UUID.randomUUID(), at = "2025-01-01", teacher = "T4",
                certId = "C4", issuedOn = "2025-01-01", expiresOn = "2027-12-31",
            ),
            Fixtures.certRevoked(
                id = UUID.randomUUID(), at = "2026-05-02", teacher = "T4",
                certId = "C4", effectiveOn = "2026-05-01",
            ),
            Fixtures.matrix(id = UUID.randomUUID(), at = "2024-12-01", version = "v1",
                course = "C-FUTURE", effectiveFrom = "2025-01-01", practiceDays = 0,
                certTypes = listOf(Fixtures.CERT_TYPE), projects = 0),
        )
        assertEquals(ItemStatus.PASS, eval(events, "T4", "C-FUTURE", "2026-05-01").certItem().status)
        assertEquals(ItemStatus.FAIL, eval(events, "T4", "C-FUTURE", "2026-05-02").certItem().status)
    }

    // ---------- 矩阵版本 ----------

    @Test
    fun `能力矩阵按 effectiveFrom 取当日最新版本`() {
        val course = "C-VERSION"
        val events = listOf(
            Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T5"),
            Fixtures.practice(UUID.randomUUID(), at = "2025-03-02", teacher = "T5",
                start = "2025-01-01", end = "2025-03-01"),
            Fixtures.practice(UUID.randomUUID(), at = "2025-04-02", teacher = "T5",
                start = "2025-02-15", end = "2025-04-01"),
            Fixtures.certIssued(UUID.randomUUID(), at = "2025-01-01", teacher = "T5",
                certId = "C5", issuedOn = "2025-01-01", expiresOn = "2027-12-31"),
            Fixtures.projectAssigned(UUID.randomUUID(), at = "2025-08-01", teacher = "T5", projectId = "P5"),
            Fixtures.projectAccepted(UUID.randomUUID(), at = "2025-09-02", teacher = "T5",
                projectId = "P5", acceptedOn = "2025-09-01"),
            Fixtures.matrix(UUID.randomUUID(), at = "2024-12-01", version = "v1",
                course = course, effectiveFrom = "2025-01-01", practiceDays = 90),
            Fixtures.matrix(UUID.randomUUID(), at = "2026-05-01", version = "v2",
                course = course, effectiveFrom = "2026-06-01", practiceDays = 120),
        )

        val beforeV2 = eval(events, "T5", course, "2026-05-31")
        assertEquals("v1", beforeV2.matrixVersion)
        assertEquals(QualificationResult.QUALIFIED, beforeV2.result)

        val onV2 = eval(events, "T5", course, "2026-06-01")
        assertEquals("v2", onV2.matrixVersion)
        assertEquals(QualificationResult.NOT_QUALIFIED, onV2.result)
        val practice = onV2.item("PRACTICE")
        assertEquals(ItemStatus.FAIL, practice.status)
        assertTrue(practice.missing!!.contains("29"), practice.missing!!)
    }

    @Test
    fun `没有生效矩阵时直接不通过并说明缺口`() {
        val events = listOf(Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T6"))
        val result = eval(events, "T6", "C-NO-MATRIX", "2026-01-01")
        assertEquals(QualificationResult.NOT_QUALIFIED, result.result)
        assertEquals(ItemStatus.FAIL, result.item("MATRIX").status)
    }

    // ---------- 重复事件（幂等重试） ----------

    @Test
    fun `重复 event_id 只投影一次`() {
        val timeline = Fixtures.t1Timeline()
        val duplicated = timeline + timeline // 每个事件出现两次
        val result = eval(duplicated, Fixtures.T1, Fixtures.COURSE, "2026-04-30")

        assertEquals(QualificationResult.QUALIFIED, result.result)
        val practice = result.item("PRACTICE")
        assertEquals(91L, Regex("共 (\\d+) 天").find(practice.message)!!.groupValues[1].toLong())
        // 证据事件 ID 去重
        assertEquals(practice.evidenceIds.size, practice.evidenceIds.distinct().size)
    }

    // ---------- 产业项目验收 ----------

    @Test
    fun `产业项目未验收或验收日晚于 asOf 不算证据`() {
        val course = "C-PROJECT"
        val matrix = Fixtures.matrix(UUID.randomUUID(), at = "2024-12-01", version = "v1",
            course = course, effectiveFrom = "2025-01-01", practiceDays = 0, projects = 1)

        val assignedOnly = listOf(
            Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = "T7"),
            Fixtures.projectAssigned(UUID.randomUUID(), at = "2025-08-01", teacher = "T7", projectId = "P7"),
            matrix,
        )
        val r1 = eval(assignedOnly, "T7", course, "2026-01-01")
        assertEquals(ItemStatus.FAIL, r1.item("PROJECT").status)
        assertTrue(r1.item("PROJECT").message.contains("尚未通过企业验收"))

        val acceptedLater = assignedOnly + Fixtures.projectAccepted(
            UUID.randomUUID(), at = "2026-03-15", teacher = "T7",
            projectId = "P7", acceptedOn = "2026-03-15",
        )
        assertEquals(ItemStatus.FAIL, eval(acceptedLater, "T7", course, "2026-03-14").item("PROJECT").status)
        assertEquals(ItemStatus.PASS, eval(acceptedLater, "T7", course, "2026-03-15").item("PROJECT").status)
    }
}
