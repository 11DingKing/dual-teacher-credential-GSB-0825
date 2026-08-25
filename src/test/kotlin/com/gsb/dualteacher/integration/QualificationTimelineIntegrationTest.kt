package com.gsb.dualteacher.integration

import com.gsb.dualteacher.Fixtures
import com.gsb.dualteacher.domain.DecisionSnapshot
import com.gsb.dualteacher.domain.ItemStatus
import com.gsb.dualteacher.domain.QualificationResult
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class QualificationTimelineIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun clean() = runBlocking { clearTables() }

    @Test
    fun `T1 三个基准日查询与证据解释符合时间线`() = withApp {
        // 事件乱序到达（吊销先于签发、验收先于委派），由投影按业务时间纠正
        postEvents(Fixtures.t1Timeline())

        // 2026-04-30：吊销 5/1 才生效，证书 6/30 才到期 → 合格
        val apr30 = qualification(Fixtures.T1, Fixtures.COURSE, "2026-04-30").body<DecisionSnapshot>()
        assertEquals(QualificationResult.QUALIFIED, apr30.result)
        assertEquals("v1", apr30.matrixVersion)
        val practiceApr = apr30.items.first { it.code == "PRACTICE" }
        assertEquals(ItemStatus.PASS, practiceApr.status)
        assertTrue(practiceApr.message.contains("91 天"), practiceApr.message)
        assertTrue(practiceApr.evidenceIds.contains(Fixtures.ID_PRACTICE_1.toString()))
        assertTrue(practiceApr.evidenceIds.contains(Fixtures.ID_PRACTICE_2.toString()))
        val certApr = apr30.items.first { it.code.startsWith("CERT_") }
        assertEquals(ItemStatus.PASS, certApr.status)
        assertEquals(listOf(Fixtures.ID_CERT_ISSUED.toString()), certApr.evidenceIds)
        assertTrue(apr30.items.none { it.missing != null })

        // 2026-05-01：吊销当天生效 → 不合格，证据包含吊销事件
        val may1 = qualification(Fixtures.T1, Fixtures.COURSE, "2026-05-01").body<DecisionSnapshot>()
        assertEquals(QualificationResult.NOT_QUALIFIED, may1.result)
        val certMay = may1.items.first { it.code.startsWith("CERT_") }
        assertEquals(ItemStatus.FAIL, certMay.status)
        assertTrue(certMay.evidenceIds.contains(Fixtures.ID_CERT_REVOKED.toString()))
        assertTrue(certMay.message.contains("吊销"))
        assertEquals(ItemStatus.PASS, may1.items.first { it.code == "PRACTICE" }.status)

        // 2026-07-01：证书已过期且早已吊销 → 不合格
        val jul1 = qualification(Fixtures.T1, Fixtures.COURSE, "2026-07-01").body<DecisionSnapshot>()
        assertEquals(QualificationResult.NOT_QUALIFIED, jul1.result)
        assertEquals(ItemStatus.FAIL, jul1.items.first { it.code.startsWith("CERT_") }.status)

        // 三个基准日各有独立 decision_id
        assertEquals(
            3,
            listOf(apr30.decisionId, may1.decisionId, jul1.decisionId).distinct().size,
        )
        assertNotEquals(apr30.decisionId, may1.decisionId)
        assertNotEquals(may1.decisionId, jul1.decisionId)
    }

    @Test
    fun `决定快照可按 decision_id 读取且内容一致`() = withApp {
        postEvents(Fixtures.t1Timeline())
        val snapshot = qualification(Fixtures.T1, Fixtures.COURSE, "2026-04-30").body<DecisionSnapshot>()

        val fetched = decision(snapshot.decisionId).body<DecisionSnapshot>()
        assertEquals(snapshot, fetched)
    }

    @Test
    fun `重复查询同一基准日返回同一份不可变快照`() = withApp {
        postEvents(Fixtures.t1Timeline())
        val first = qualification(Fixtures.T1, Fixtures.COURSE, "2026-04-30").body<DecisionSnapshot>()
        val second = qualification(Fixtures.T1, Fixtures.COURSE, "2026-04-30").body<DecisionSnapshot>()
        assertEquals(first.decisionId, second.decisionId)
        assertEquals(first, second)
    }

    @Test
    fun `as_of 查询参数别名可用`() = withApp {
        postEvents(Fixtures.t1Timeline())
        val response = client.get("/api/v1/teachers/${Fixtures.T1}/qualification?course=${Fixtures.COURSE}&as_of=2026-04-30")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(QualificationResult.QUALIFIED, response.body<DecisionSnapshot>().result)
    }

    @Test
    fun `未注册教师返回 404，缺少参数返回 400`() = withApp {
        postEvents(Fixtures.t1Timeline())

        val notFound = qualification("T-UNKNOWN", Fixtures.COURSE, "2026-04-30")
        assertEquals(HttpStatusCode.NotFound, notFound.status)

        val badRequest = client.get("/api/v1/teachers/${Fixtures.T1}/qualification?course=${Fixtures.COURSE}")
        assertEquals(HttpStatusCode.BadRequest, badRequest.status)

        val badDate = client.get("/api/v1/teachers/${Fixtures.T1}/qualification?course=${Fixtures.COURSE}&asOf=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, badDate.status)
    }
}
