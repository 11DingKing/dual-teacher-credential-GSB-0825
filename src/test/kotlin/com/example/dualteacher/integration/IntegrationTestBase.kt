package com.example.dualteacher.integration

import com.example.dualteacher.appModule
import com.example.dualteacher.config.AppConfig
import com.example.dualteacher.config.DatabaseFactory
import com.example.dualteacher.domain.EventRequest
import com.example.dualteacher.domain.EventType
import com.example.dualteacher.service.IngestionService
import com.example.dualteacher.service.QualificationService
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * 集成测试基类：需要 DATABASE_URL 指向一个**专用测试库**（每个用例前 TRUNCATE 全部表）。
 * 未设置 DATABASE_URL 时，集成测试整体跳过（单元测试不受影响）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IntegrationTestBase {

    protected lateinit var db: Database
    protected lateinit var ingestion: IngestionService
    protected lateinit var qualification: QualificationService

    @BeforeAll
    fun initDb() {
        db = DatabaseFactory.init(AppConfig.fromEnv())
        ingestion = IngestionService(db)
        qualification = QualificationService(db)
    }

    @BeforeEach
    fun cleanDatabase() {
        transaction(db) {
            exec(
                "TRUNCATE TABLE decisions, industry_projects, course_matrices, " +
                    "certifications, certification_revocations, enterprise_practices, teachers, events CASCADE"
            )
        }
    }

    // ---------- HTTP 辅助 ----------

    protected fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    protected suspend fun HttpClient.postEvent(eventId: String, type: String, payloadJson: String): JsonObject {
        val response = post("/events") {
            contentType(ContentType.Application.Json)
            setBody("""{"event_id":"$eventId","event_type":"$type","payload":$payloadJson}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, "POST /events $eventId -> ${response.bodyAsText()}")
        return response.body()
    }

    protected suspend fun HttpClient.qualify(teacherId: String, courseId: String, asOf: String): JsonObject {
        val response: HttpResponse = get("/teachers/$teacherId/qualifications?course_id=$courseId&as_of=$asOf")
        assertEquals(HttpStatusCode.OK, response.status, "qualify($teacherId,$courseId,$asOf) -> ${response.bodyAsText()}")
        return response.body()
    }

    // ---------- 服务级数据准备 ----------

    protected fun event(id: String, type: String, payloadJson: String) = EventRequest(
        eventId = id,
        eventType = EventType.valueOf(type),
        payload = Json.parseToJsonElement(payloadJson).jsonObject,
    )

    /** T1 标准数据：吊销事件(e-004)先于签发事件(e-005)到达，验证乱序投影。 */
    protected fun seedStandardT1() {
        ingestion.ingest(event("e-001", "TEACHER_REGISTERED", """{"teacher_id":"T1","name":"张三"}"""))
        ingestion.ingest(event("e-002", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-01-01","end_date":"2025-03-01"}"""))
        ingestion.ingest(event("e-003", "ENTERPRISE_PRACTICE_RECORDED", """{"teacher_id":"T1","company":"ACME","start_date":"2025-02-15","end_date":"2025-04-01"}"""))
        ingestion.ingest(event("e-004", "CERTIFICATION_REVOKED", """{"cert_code":"C1","effective_date":"2026-05-01","reason":"annual audit"}"""))
        ingestion.ingest(event("e-005", "CERTIFICATION_ISSUED", """{"teacher_id":"T1","cert_code":"C1","issued_date":"2025-06-01","expiry_date":"2026-06-30"}"""))
        ingestion.ingest(event("e-006", "INDUSTRY_PROJECT_RECORDED", """{"teacher_id":"T1","project_name":"P-100","role":"MENTOR","accepted_by_enterprise":true,"acceptance_date":"2025-09-01"}"""))
        ingestion.ingest(event("e-007", "COURSE_MATRIX_PUBLISHED", """{"course_id":"COURSE-1","version":1,"effective_from":"2025-01-01","requirements":{"min_practice_days":90,"required_certifications":["C1"],"required_project_roles":["MENTOR"]}}"""))
    }
}
