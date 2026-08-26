package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import com.example.dualteacher.db.EnterprisePractices
import com.example.dualteacher.db.Events
import com.example.dualteacher.db.Teachers
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals

/** 重复事件：同一 event_id 重试必须幂等，不重复投影。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class IdempotencyTest : IntegrationTestBase() {

    @Test
    fun `duplicate event is idempotent and projection stays single`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        val first = client.postEvent("e-001", "TEACHER_REGISTERED", """{"teacher_id":"T1","name":"张三"}""")
        assertEquals("recorded", first["status"]!!.jsonPrimitive.content)

        // 完全相同的重试
        val retry = client.postEvent("e-001", "TEACHER_REGISTERED", """{"teacher_id":"T1","name":"张三"}""")
        assertEquals("duplicate", retry["status"]!!.jsonPrimitive.content)

        // 相同 event_id 但不同载荷：仍按幂等处理，保留首条
        val conflicting = client.postEvent("e-001", "TEACHER_REGISTERED", """{"teacher_id":"T1","name":"改名"}""")
        assertEquals("duplicate", conflicting["status"]!!.jsonPrimitive.content)

        transaction(db) {
            assertEquals(1, Events.selectAll().count())
            assertEquals(1, Teachers.selectAll().count())
            assertEquals("张三", Teachers.selectAll().single()[Teachers.name])
        }

        // 业务事件重复提交同样幂等
        client.postEvent("e-002", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}""")
        val dup = client.postEvent("e-002", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}""")
        assertEquals("duplicate", dup["status"]!!.jsonPrimitive.content)
        transaction(db) {
            assertEquals(1, EnterprisePractices.selectAll().count())
        }
    }
}
