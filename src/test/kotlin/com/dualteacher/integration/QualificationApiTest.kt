package com.dualteacher.integration

import com.dualteacher.api.EventRequest
import com.dualteacher.api.QualificationResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class QualificationApiTest : IntegrationTestBase() {

    private fun eventReq(
        eventId: String,
        eventType: String,
        aggregateId: String,
        occurredAt: String,
        payload: JsonObject
    ) = EventRequest(
        eventId = eventId,
        eventType = eventType,
        aggregateId = aggregateId,
        occurredAt = LocalDate.parse(occurredAt),
        payload = payload
    )

    @Test
    fun `health endpoint returns OK`() = apiTest { client ->
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `post event and query qualification via HTTP`() = apiTest { client ->
        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("e1", "TEACHER_CREATED", "T-API", "2025-01-01",
                buildJsonObject { put("name", JsonPrimitive("API Teacher")) }))
        }

        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("e2", "PRACTICE_RECORDED", "T-API", "2025-02-01",
                buildJsonObject {
                    put("intervalId", JsonPrimitive("PI1"))
                    put("startDate", JsonPrimitive("2025-01-01"))
                    put("endDate", JsonPrimitive("2025-04-01"))
                    put("company", JsonPrimitive("Acme"))
                }))
        }

        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("e3", "CERTIFICATION_ISSUED", "T-API", "2025-01-15",
                buildJsonObject {
                    put("certificationId", JsonPrimitive("CERT1"))
                    put("certType", JsonPrimitive("EQUIPMENT"))
                    put("expiryDate", JsonPrimitive("2027-01-01"))
                    put("issuer", JsonPrimitive("Issuer"))
                }))
        }

        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("e4", "MATRIX_PUBLISHED", "COURSE-API", "2025-01-01",
                buildJsonObject {
                    put("matrixId", JsonPrimitive("M1"))
                    put("courseId", JsonPrimitive("COURSE-API"))
                    put("version", JsonPrimitive(1))
                    putJsonObject("requirements") {
                        put("minPracticeDays", JsonPrimitive(90))
                        putJsonArray("requiredCertTypes") { add(JsonPrimitive("EQUIPMENT")) }
                        put("minAcceptedProjects", JsonPrimitive(0))
                    }
                }))
        }

        val response = client.get("/api/v1/teachers/T-API/qualification?course_id=COURSE-API&as_of=2026-04-30")
        assertEquals(HttpStatusCode.OK, response.status)

        val result = response.body<QualificationResponse>()
        assertEquals("PASS", result.overallResult)
        assertEquals("T-API", result.teacherId)
        assertEquals("COURSE-API", result.courseId)
        assertNotNull(result.decisionId)
        assertTrue(result.items.isNotEmpty())
    }

    @Test
    fun `duplicate event via HTTP returns 200 not 201`() = apiTest { client ->
        val event = eventReq("dup-1", "TEACHER_CREATED", "T-DUP", "2025-01-01",
            buildJsonObject { put("name", JsonPrimitive("Dup")) })

        val r1 = client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(event)
        }
        assertEquals(HttpStatusCode.Created, r1.status)

        val r2 = client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(event)
        }
        assertEquals(HttpStatusCode.OK, r2.status)
    }

    @Test
    fun `retrieve decision by ID returns immutable snapshot`() = apiTest { client ->
        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("d1", "TEACHER_CREATED", "T-DEC", "2025-01-01",
                buildJsonObject { put("name", JsonPrimitive("Dec Teacher")) }))
        }
        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(eventReq("d2", "MATRIX_PUBLISHED", "COURSE-DEC", "2025-01-01",
                buildJsonObject {
                    put("matrixId", JsonPrimitive("M1"))
                    put("courseId", JsonPrimitive("COURSE-DEC"))
                    put("version", JsonPrimitive(1))
                    putJsonObject("requirements") {
                        put("minPracticeDays", JsonPrimitive(30))
                        putJsonArray("requiredCertTypes") {}
                        put("minAcceptedProjects", JsonPrimitive(0))
                    }
                }))
        }

        val qualResp = client.get("/api/v1/teachers/T-DEC/qualification?course_id=COURSE-DEC&as_of=2026-01-01")
            .body<QualificationResponse>()

        val decisionId = qualResp.decisionId

        val decisionResp = client.get("/api/v1/decisions/$decisionId")
        assertEquals(HttpStatusCode.OK, decisionResp.status)

        val decision = decisionResp.body<QualificationResponse>()
        assertEquals(decisionId, decision.decisionId)
        assertEquals(qualResp.overallResult, decision.overallResult)
    }

    @Test
    fun `missing course_id returns 400`() = apiTest { client ->
        val response = client.get("/api/v1/teachers/T1/qualification?as_of=2026-01-01")
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `non-existent decision returns 404`() = apiTest { client ->
        val response = client.get("/api/v1/decisions/00000000-0000-0000-0000-000000000000")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
