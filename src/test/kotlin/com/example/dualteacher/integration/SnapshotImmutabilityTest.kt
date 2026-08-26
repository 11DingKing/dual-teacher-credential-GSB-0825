package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 快照不可变：补录业务时间更早的材料，不回溯修改已存在的决定。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class SnapshotImmutabilityTest : IntegrationTestBase() {

    @Test
    fun `backfilled revocation does not change an existing decision`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        client.postEvent("e-201", "TEACHER_REGISTERED", """{"teacher_id":"T3","name":"王五"}""")
        client.postEvent("e-202", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T3","company":"ACME","start_date":"2025-01-01","end_date":"2025-04-01"}""")
        client.postEvent("e-203", "CERTIFICATION_ISSUED", """{"teacher_id":"T3","cert_code":"C1","issued_date":"2025-06-01","expiry_date":"2026-06-30"}""")
        client.postEvent("e-204", "INDUSTRY_PROJECT_RECORDED", """{"teacher_id":"T3","project_name":"P-9","role":"MENTOR","accepted_by_enterprise":true,"acceptance_date":"2025-09-01"}""")
        client.postEvent("e-205", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-1","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":90,"required_certifications":["C1"],"required_project_roles":["MENTOR"]}}""")

        // 先判定 2026-04-30 → 通过（此刻还没有任何吊销）
        val first = client.qualify("T3", "COURSE-1", "2026-04-30")
        assertTrue(first["qualified"]!!.jsonPrimitive.boolean)
        assertTrue(first["created"]!!.jsonPrimitive.boolean)

        // 补录一条业务时间更早的吊销（effective 2026-04-01，晚于决定才到达）
        client.postEvent("e-206", "CERTIFICATION_REVOKED", """{"cert_code":"C1","effective_date":"2026-04-01","reason":"late filing"}""")

        // 旧决定不变：同一 decision_id、仍然通过、逐项内容一致
        val again = client.qualify("T3", "COURSE-1", "2026-04-30")
        assertEquals(first["decision_id"]!!.jsonPrimitive.content, again["decision_id"]!!.jsonPrimitive.content)
        assertFalse(again["created"]!!.jsonPrimitive.boolean)
        assertTrue(again["qualified"]!!.jsonPrimitive.boolean)
        assertEquals(first["items"]!!.jsonArray.toString(), again["items"]!!.jsonArray.toString())

        // 但新的 as_of 会反映补录的吊销 → 不通过
        val newKey = client.qualify("T3", "COURSE-1", "2026-04-02")
        assertFalse(newKey["qualified"]!!.jsonPrimitive.boolean)
        val cert = newKey["items"]!!.jsonArray
            .single { it.jsonObject["subject"]!!.jsonPrimitive.content == "C1" }.jsonObject
        assertEquals("CERT_REVOKED", cert["reason_code"]!!.jsonPrimitive.content)

        // 旧快照仍可按 decision_id 原样读取
        val snapshot = client.get("/decisions/${first["decision_id"]!!.jsonPrimitive.content}").body<JsonObject>()
        assertTrue(snapshot["qualified"]!!.jsonPrimitive.boolean)
    }
}
