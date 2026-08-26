package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 健康检查、OpenAPI 与错误处理冒烟测试。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class ApiSmokeTest : IntegrationTestBase() {

    @Test
    fun `health endpoint reports UP`() = testApplication {
        application { appModule(db) }
        val response = jsonClient().get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UP", response.body<JsonObject>()["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openapi spec is served`() = testApplication {
        application { appModule(db) }
        val response = jsonClient().get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Dual-Teacher Credential"))
    }

    @Test
    fun `malformed requests yield 400`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        val badJson = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("{not json")
        }
        assertEquals(HttpStatusCode.BadRequest, badJson.status)

        val badType = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"event_id":"x","event_type":"NOPE","payload":{}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badType.status)

        val missingAsOf = client.get("/teachers/T1/qualifications?course_id=C")
        assertEquals(HttpStatusCode.BadRequest, missingAsOf.status)

        val badAsOf = client.get("/teachers/T1/qualifications?course_id=C&as_of=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, badAsOf.status)
    }

    @Test
    fun `unknown teacher yields 404 on query and 400 on evidence ingestion`() = testApplication {
        application { appModule(db) }
        val client = jsonClient()

        val response = client.get("/teachers/NOPE/qualifications?course_id=C&as_of=2026-01-01")
        assertEquals(HttpStatusCode.NotFound, response.status)

        val practiceForGhost = client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"event_id":"e-900","event_type":"ENTERPRISE_PRACTICE_RECORDED","payload":{"teacher_id":"NOPE","company":"ACME","start_date":"2025-01-01","end_date":"2025-02-01"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, practiceForGhost.status)
    }
}
