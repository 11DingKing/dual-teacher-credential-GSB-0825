package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 题目给定的 T1 场景（端到端 HTTP）：
 * - 两段重叠企业实践 2025-01-01..2025-03-01 与 2025-02-15..2025-04-01（合并后 91 天只算一次）
 * - 认证 C1 于 2026-06-30 到期；吊销事件先到达，业务生效时间 2026-05-01
 * - 分别查询 2026-04-30 / 2026-05-01 / 2026-07-01
 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class T1ScenarioTest : IntegrationTestBase() {

    @Test
    fun `T1 out-of-order revocation and timeline queries`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        // 乱序摄入：吊销(e-004)先于签发(e-005)到达
        client.postEvent("e-001", "TEACHER_REGISTERED", """{"teacher_id":"T1","name":"张三"}""")
        client.postEvent("e-002", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}""")
        client.postEvent("e-003", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-02-15","end_date":"2025-04-01"}""")
        client.postEvent("e-004", "CERTIFICATION_REVOKED", """{"cert_code":"C1","effective_date":"2026-05-01","reason":"annual audit"}""")
        client.postEvent("e-005", "CERTIFICATION_ISSUED", """{"teacher_id":"T1","cert_code":"C1","issued_date":"2025-06-01","expiry_date":"2026-06-30"}""")
        client.postEvent("e-006", "INDUSTRY_PROJECT_RECORDED", """{"teacher_id":"T1","project_name":"P-100","role":"MENTOR","accepted_by_enterprise":true,"acceptance_date":"2025-09-01"}""")
        client.postEvent("e-007", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-1","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":90,"required_certifications":["C1"],"required_project_roles":["MENTOR"]}}""")

        // 2026-04-30：吊销尚未生效 → 具备资格
        val apr30 = client.qualify("T1", "COURSE-1", "2026-04-30")
        assertTrue(apr30["qualified"]!!.jsonPrimitive.boolean)
        assertTrue(apr30["created"]!!.jsonPrimitive.boolean)
        assertEquals(1, apr30["matrix_version"]!!.jsonPrimitive.int)
        val apr30Items = apr30["items"]!!.jsonArray
        assertEquals(3, apr30Items.size)

        val practice = apr30Items.single { it.jsonObject["type"]!!.jsonPrimitive.content == "practice" }.jsonObject
        assertEquals("PASS", practice["status"]!!.jsonPrimitive.content)
        assertEquals("91 days", practice["actual"]!!.jsonPrimitive.content)
        assertEquals(2, practice["evidence_ids"]!!.jsonArray.size, "两段实践区间都应列为证据")

        val certApr = apr30Items.single { it.jsonObject["subject"]!!.jsonPrimitive.content == "C1" }.jsonObject
        assertEquals("PASS", certApr["status"]!!.jsonPrimitive.content)

        // 2026-05-01：吊销生效 → 不具备资格，证据包含吊销记录
        val may1 = client.qualify("T1", "COURSE-1", "2026-05-01")
        assertFalse(may1["qualified"]!!.jsonPrimitive.boolean)
        val certMay = may1["items"]!!.jsonArray.single { it.jsonObject["subject"]!!.jsonPrimitive.content == "C1" }.jsonObject
        assertEquals("FAIL", certMay["status"]!!.jsonPrimitive.content)
        assertEquals("CERT_REVOKED", certMay["reason_code"]!!.jsonPrimitive.content)
        assertTrue(certMay["missing"]!!.jsonPrimitive.content.contains("2026-05-01"))
        assertEquals(2, certMay["evidence_ids"]!!.jsonArray.size, "证书与吊销记录都应列为证据")
        // 实践与项目仍通过
        assertEquals(
            "PASS",
            may1["items"]!!.jsonArray.single { it.jsonObject["type"]!!.jsonPrimitive.content == "practice" }.jsonObject["status"]!!.jsonPrimitive.content
        )

        // 2026-07-01：证书已过期（且已吊销）→ 不具备资格，原因为过期
        val jul1 = client.qualify("T1", "COURSE-1", "2026-07-01")
        assertFalse(jul1["qualified"]!!.jsonPrimitive.boolean)
        val certJul = jul1["items"]!!.jsonArray.single { it.jsonObject["subject"]!!.jsonPrimitive.content == "C1" }.jsonObject
        assertEquals("CERT_EXPIRED", certJul["reason_code"]!!.jsonPrimitive.content)
        assertTrue(certJul["missing"]!!.jsonPrimitive.content.contains("2026-06-30"))

        // 同一 as_of 再次查询 → 同一不可变快照
        val apr30Again = client.qualify("T1", "COURSE-1", "2026-04-30")
        assertEquals(apr30["decision_id"]!!.jsonPrimitive.content, apr30Again["decision_id"]!!.jsonPrimitive.content)
        assertFalse(apr30Again["created"]!!.jsonPrimitive.boolean)

        // 快照可按 decision_id 读取
        val decisionResponse = client.get("/decisions/${apr30["decision_id"]!!.jsonPrimitive.content}")
        assertEquals(HttpStatusCode.OK, decisionResponse.status)
        val snapshot = decisionResponse.body<JsonObject>()
        assertTrue(snapshot["qualified"]!!.jsonPrimitive.boolean)
        assertEquals("2026-04-30", snapshot["as_of"]!!.jsonPrimitive.content)

        // 档案证据与判定中的 evidence_ids 可对照
        val portfolio = client.get("/teachers/T1/portfolio").body<JsonObject>()
        assertEquals(2, portfolio["practices"]!!.jsonArray.size)
        assertEquals(1, portfolio["certifications"]!!.jsonArray.size)
        assertEquals(1, portfolio["revocations"]!!.jsonArray.size)
        assertEquals(1, portfolio["projects"]!!.jsonArray.size)
        val portfolioIds = (
            portfolio["practices"]!!.jsonArray + portfolio["certifications"]!!.jsonArray
            ).map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertTrue(practice["evidence_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.all { it in portfolioIds })
        assertTrue(certApr["evidence_ids"]!!.jsonArray.map { it.jsonPrimitive.content }.all { it in portfolioIds })
        // 吊销记录携带其来源 event_id，可回溯
        assertEquals(
            "e-004",
            portfolio["revocations"]!!.jsonArray.single().jsonObject["event_id"]!!.jsonPrimitive.content
        )
    }
}
