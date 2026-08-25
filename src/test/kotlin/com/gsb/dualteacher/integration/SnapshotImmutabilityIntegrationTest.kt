package com.gsb.dualteacher.integration

import com.gsb.dualteacher.Fixtures
import com.gsb.dualteacher.domain.DecisionSnapshot
import com.gsb.dualteacher.domain.ItemStatus
import com.gsb.dualteacher.domain.QualificationResult
import io.ktor.client.call.body
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SnapshotImmutabilityIntegrationTest : IntegrationTestBase() {

    private val teacher = "T8"
    private val course = "C-IMMUTABLE"

    @BeforeEach
    fun clean() = runBlocking { clearTables() }

    @Test
    fun `补录材料不能反过来改变旧决定，但新基准日可以采用新证据`() = withApp {
        // 初始材料不全：只有 30 天实践，无认证、无项目 → NOT_QUALIFIED
        postEvents(
            listOf(
                Fixtures.teacherRegistered(id = UUID.randomUUID(), teacher = teacher),
                Fixtures.matrix(
                    id = UUID.randomUUID(), at = "2024-12-01", version = "v1",
                    course = course, effectiveFrom = "2025-01-01", practiceDays = 90,
                ),
                Fixtures.practice(
                    id = UUID.randomUUID(), at = "2026-02-01", teacher = teacher,
                    start = "2026-01-01", end = "2026-01-30",
                ),
            ),
        )

        val old = qualification(teacher, course, "2026-04-30").body<DecisionSnapshot>()
        assertEquals(QualificationResult.NOT_QUALIFIED, old.result)
        val oldJson = old.toString()

        // 事后补录：业务时间都早于 2026-04-30（迟到的旧事实），若重新投影当日本该合格
        postEvents(
            listOf(
                Fixtures.practice(
                    id = UUID.randomUUID(), at = "2025-06-02", teacher = teacher,
                    start = "2025-02-01", end = "2025-06-01",
                ),
                Fixtures.certIssued(
                    id = UUID.randomUUID(), at = "2025-01-02", teacher = teacher,
                    certId = "C8", issuedOn = "2025-01-02", expiresOn = "2027-12-31",
                ),
                Fixtures.projectAssigned(
                    id = UUID.randomUUID(), at = "2025-08-01", teacher = teacher, projectId = "P8",
                ),
                Fixtures.projectAccepted(
                    id = UUID.randomUUID(), at = "2025-09-02", teacher = teacher,
                    projectId = "P8", acceptedOn = "2025-09-01",
                ),
            ),
        )

        // 重复查询同一基准日：旧决定原样返回（decision_id 与内容都不变）
        val repeated = qualification(teacher, course, "2026-04-30").body<DecisionSnapshot>()
        assertEquals(old.decisionId, repeated.decisionId)
        assertEquals(oldJson, repeated.toString())

        val fetched = decision(old.decisionId).body<DecisionSnapshot>()
        assertEquals(old, fetched)

        // 新基准日（补录之后）产生新决定，采用补录的证据 → QUALIFIED
        val later = qualification(teacher, course, "2026-08-01").body<DecisionSnapshot>()
        assertNotEquals(old.decisionId, later.decisionId)
        assertEquals(QualificationResult.QUALIFIED, later.result)
        assertEquals(ItemStatus.PASS, later.items.first { it.code == "PRACTICE" }.status)
        assertEquals(ItemStatus.PASS, later.items.first { it.code.startsWith("CERT_") }.status)
        assertEquals(ItemStatus.PASS, later.items.first { it.code == "PROJECT" }.status)
    }
}
