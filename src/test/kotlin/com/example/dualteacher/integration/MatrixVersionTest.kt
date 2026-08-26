package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 矩阵版本：按 as_of 选择已生效的最新版本，即使版本乱序发布。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class MatrixVersionTest : IntegrationTestBase() {

    @Test
    fun `matrix version is selected by as_of even when published out of order`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        client.postEvent("e-101", "TEACHER_REGISTERED", """{"teacher_id":"T2","name":"李四"}""")
        // 32 天实践：满足 v1(30 天)，不满足 v2(90 天)
        client.postEvent("e-102", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T2","company":"ACME","start_date":"2025-01-01","end_date":"2025-02-01"}""")

        // 乱序发布：v2 先到达，v1 后到达
        client.postEvent("e-103", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-2","version":2,"effective_from":"2026-01-01","requirements":{"min_practice_days":90}}""")
        client.postEvent("e-104", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-2","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":30}}""")

        // as_of 落在 v1 生效期 → 用 v1 判定 → 通过
        val v1Decision = client.qualify("T2", "COURSE-2", "2025-06-01")
        assertEquals(1, v1Decision["matrix_version"]!!.jsonPrimitive.int)
        assertTrue(v1Decision["qualified"]!!.jsonPrimitive.boolean)

        // as_of 落在 v2 生效期 → 用 v2 判定 → 不通过
        val v2Decision = client.qualify("T2", "COURSE-2", "2026-01-01")
        assertEquals(2, v2Decision["matrix_version"]!!.jsonPrimitive.int)
        assertFalse(v2Decision["qualified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `no matrix effective at as_of yields 404`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        client.postEvent("e-101", "TEACHER_REGISTERED", """{"teacher_id":"T2","name":"李四"}""")
        client.postEvent("e-103", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-2","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":30}}""")

        val response = client.get("/teachers/T2/qualifications?course_id=COURSE-2&as_of=2024-12-31")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
