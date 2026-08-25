package com.dualteacher.integration

import com.dualteacher.domain.QualificationDecision
import com.dualteacher.module
import com.dualteacher.repo.DecisionRepository
import com.dualteacher.repo.EventStore
import com.dualteacher.service.QualificationService
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualificationApiTest : IntegrationTestBase() {

    private lateinit var service: QualificationService
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @BeforeEach
    fun setup() {
        cleanDatabase()
        service = QualificationService(EventStore(), DecisionRepository())
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { classDiscriminator = "type" }) }
    }

    @Test
    fun `health endpoint reports UP`() = testApplication {
        application { module(service, factory) }
        val resp = client.get("/health")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("UP"))
    }

    @Test
    fun `post event and query qualification via HTTP`() = testApplication {
        application { module(service, factory) }
        val client = jsonClient()

        val matrix = """
            {"event_id":"h-matrix","occurred_at":"2025-12-01T00:00:00Z",
             "payload":{"type":"CompetencyMatrixPublished","course_id":"C-RISK","version":1,
                        "effective_from":"2026-01-01","min_practice_days":10}}
        """.trimIndent()
        val practice = """
            {"event_id":"h-p1","occurred_at":"2025-03-05T00:00:00Z",
             "payload":{"type":"PracticeRecorded","teacher_id":"T1","enterprise":"ACME",
                        "start_date":"2025-01-01","end_date":"2025-03-01"}}
        """.trimIndent()

        for (body in listOf(matrix, practice)) {
            val r = client.post("/events") { contentType(ContentType.Application.Json); setBody(body) }
            assertEquals(HttpStatusCode.OK, r.status)
        }

        val resp = client.get("/teachers/T1/qualification?course_id=C-RISK&as_of=2026-04-30")
        assertEquals(HttpStatusCode.OK, resp.status)
        val decision = json.decodeFromString(QualificationDecision.serializer(), resp.bodyAsText())
        assertEquals("T1", decision.teacherId)
        assertTrue(decision.qualified)
    }

    @Test
    fun `duplicate event via HTTP is reported as not stored`() = testApplication {
        application { module(service, factory) }
        val body = """
            {"event_id":"h-dup","occurred_at":"2026-01-01T00:00:00Z",
             "payload":{"type":"TeacherRegistered","teacher_id":"T1","name":"One"}}
        """.trimIndent()
        val first = client.post("/events") { contentType(ContentType.Application.Json); setBody(body) }
        val second = client.post("/events") { contentType(ContentType.Application.Json); setBody(body) }
        assertTrue(first.bodyAsText().contains("\"stored\":true"))
        assertTrue(second.bodyAsText().contains("\"stored\":false"))
    }

    @Test
    fun `missing course_id returns 400`() = testApplication {
        application { module(service, factory) }
        val resp = client.get("/teachers/T1/qualification?as_of=2026-04-30")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invalid as_of returns 400`() = testApplication {
        application { module(service, factory) }
        val resp = client.get("/teachers/T1/qualification?course_id=C-RISK&as_of=not-a-date")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `retrieve decision by ID returns immutable snapshot`() = testApplication {
        application { module(service, factory) }
        val client = jsonClient()
        client.post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"event_id":"g-matrix","occurred_at":"2025-12-01T00:00:00Z","payload":{"type":"CompetencyMatrixPublished","course_id":"C-RISK","version":1,"effective_from":"2026-01-01","min_practice_days":0}}""")
        }
        val q = client.get("/teachers/T1/qualification?course_id=C-RISK&as_of=2026-04-30")
        val decision = json.decodeFromString(QualificationDecision.serializer(), q.bodyAsText())

        val byId = client.get("/decisions/${decision.decisionId}")
        assertEquals(HttpStatusCode.OK, byId.status)
        val fetched = json.decodeFromString(QualificationDecision.serializer(), byId.bodyAsText())
        assertEquals(decision, fetched)
    }

    @Test
    fun `unknown decision id returns 404`() = testApplication {
        application { module(service, factory) }
        val resp = client.get("/decisions/does-not-exist")
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `openapi spec is served`() = testApplication {
        application { module(service, factory) }
        val resp = client.get("/openapi.yaml")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("Dual-Teacher Credential Service"))
    }
}
