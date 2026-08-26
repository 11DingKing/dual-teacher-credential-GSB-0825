package com.dualteacher.integration

import com.dualteacher.api.EventRequest
import com.dualteacher.api.QualificationResponse
import com.dualteacher.domain.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate

class T1QualificationScenarioTest : IntegrationTestBase() {

    private fun postEvent(
        eventId: String,
        eventType: EventType,
        aggregateId: String,
        occurredAt: String,
        payloadBuilder: JsonObjectBuilder.() -> Unit
    ) = runBlocking {
        val payload = buildJsonObject(payloadBuilder)
        eventStore.append(
            EventEnvelope(
                eventId = eventId,
                eventType = eventType,
                aggregateId = aggregateId,
                occurredAt = LocalDate.parse(occurredAt),
                payload = payload
            )
        )
    }

    private fun setupT1Data() {
        runBlocking {
            postEvent("evt-t1", EventType.TEACHER_CREATED, "T1", "2024-09-01") {
                put("name", JsonPrimitive("张老师"))
            }

            postEvent(
                "evt-practice-1", EventType.PRACTICE_RECORDED, "T1",
                "2025-04-02"
            ) {
                put("intervalId", JsonPrimitive("PI1"))
                put("startDate", JsonPrimitive("2025-01-01"))
                put("endDate", JsonPrimitive("2025-03-01"))
                put("company", JsonPrimitive("华东数控集团"))
            }

            postEvent(
                "evt-practice-2", EventType.PRACTICE_RECORDED, "T1",
                "2025-04-03"
            ) {
                put("intervalId", JsonPrimitive("PI2"))
                put("startDate", JsonPrimitive("2025-02-15"))
                put("endDate", JsonPrimitive("2025-04-01"))
                put("company", JsonPrimitive("华东数控集团"))
            }

            postEvent(
                "evt-cert-issue", EventType.CERTIFICATION_ISSUED, "T1",
                "2025-06-15"
            ) {
                put("certificationId", JsonPrimitive("C1"))
                put("certType", JsonPrimitive("EQUIPMENT_OPERATION"))
                put("expiryDate", JsonPrimitive("2026-06-30"))
                put("issuer", JsonPrimitive("国家装备制造认证中心"))
            }

            // Revocation arrives early (received before its effective date)
            postEvent(
                "evt-cert-revoke", EventType.CERTIFICATION_REVOKED, "T1",
                "2026-05-01"
            ) {
                put("certificationId", JsonPrimitive("C1"))
                put("reason", JsonPrimitive("设备认证年度复核未通过"))
            }

            postEvent(
                "evt-project-rec", EventType.PROJECT_RECORDED, "T1",
                "2025-09-01"
            ) {
                put("projectId", JsonPrimitive("PR1"))
                put("projectName", JsonPrimitive("智能制造产线升级"))
                put("role", JsonPrimitive("技术负责人"))
                put("company", JsonPrimitive("华东数控集团"))
            }

            postEvent(
                "evt-project-acc", EventType.PROJECT_ACCEPTED, "T1",
                "2025-12-20"
            ) {
                put("projectId", JsonPrimitive("PR1"))
            }

            postEvent(
                "evt-matrix-v1", EventType.MATRIX_PUBLISHED, "COURSE_HIGH_RISK",
                "2025-01-01"
            ) {
                put("matrixId", JsonPrimitive("M1"))
                put("courseId", JsonPrimitive("COURSE_HIGH_RISK"))
                put("version", JsonPrimitive(1))
                put("requirements", JsonObject(mapOf(
                    "minPracticeDays" to JsonPrimitive(90),
                    "requiredCertTypes" to kotlinx.serialization.json.JsonArray(
                        listOf(JsonPrimitive("EQUIPMENT_OPERATION"))
                    ),
                    "minAcceptedProjects" to JsonPrimitive(1)
                )))
            }
        }
    }

    @Test
    fun `T1 query on 2026-04-30 should PASS - certification valid before revocation effective date`() {
        setupT1Data()

        runBlocking {
            val decision = service.queryQualification(
                teacherId = "T1",
                courseId = "COURSE_HIGH_RISK",
                asOf = LocalDate.parse("2026-04-30")
            )

            assertEquals("PASS", decision.overallResult.name)
            val items = decision.snapshot.items

            val practiceItem = items.first { it.key == "practice" }
            assertEquals("PASS", practiceItem.status)
            assertEquals("91 天", practiceItem.actual)

            val certItem = items.first { it.key == "certification:EQUIPMENT_OPERATION" }
            assertEquals("PASS", certItem.status)
            assertNull(certItem.missing)

            val projectItem = items.first { it.key == "projects" }
            assertEquals("PASS", projectItem.status)
        }
    }

    @Test
    fun `T1 query on 2026-05-01 should FAIL - revocation effective on this date`() {
        setupT1Data()

        runBlocking {
            val decision = service.queryQualification(
                teacherId = "T1",
                courseId = "COURSE_HIGH_RISK",
                asOf = LocalDate.parse("2026-05-01")
            )

            assertEquals("FAIL", decision.overallResult.name)
            val certItem = decision.snapshot.items.first {
                it.key == "certification:EQUIPMENT_OPERATION"
            }
            assertEquals("FAIL", certItem.status)
            assertTrue(certItem.actual.contains("吊销"))
        }
    }

    @Test
    fun `T1 query on 2026-07-01 should FAIL - certification expired and revoked`() {
        setupT1Data()

        runBlocking {
            val decision = service.queryQualification(
                teacherId = "T1",
                courseId = "COURSE_HIGH_RISK",
                asOf = LocalDate.parse("2026-07-01")
            )

            assertEquals("FAIL", decision.overallResult.name)
            val certItem = decision.snapshot.items.first {
                it.key == "certification:EQUIPMENT_OPERATION"
            }
            assertEquals("FAIL", certItem.status)
        }
    }

    @Test
    fun `each query creates an immutable decision snapshot with unique decision_id`() {
        setupT1Data()

        runBlocking {
            val d1 = service.queryQualification("T1", "COURSE_HIGH_RISK", LocalDate.parse("2026-04-30"))
            val d2 = service.queryQualification("T1", "COURSE_HIGH_RISK", LocalDate.parse("2026-05-01"))
            val d3 = service.queryQualification("T1", "COURSE_HIGH_RISK", LocalDate.parse("2026-07-01"))

            assertNotEquals(d1.decisionId, d2.decisionId)
            assertNotEquals(d2.decisionId, d3.decisionId)
            assertEquals("PASS", d1.overallResult.name)
            assertEquals("FAIL", d2.overallResult.name)
            assertEquals("FAIL", d3.overallResult.name)
        }
    }

    @Test
    fun `evidence ids are present in snapshot items`() {
        setupT1Data()

        runBlocking {
            val decision = service.queryQualification("T1", "COURSE_HIGH_RISK", LocalDate.parse("2026-04-30"))

            val practiceItem = decision.snapshot.items.first { it.key == "practice" }
            assertTrue(practiceItem.evidenceIds.contains("evt-practice-1"))
            assertTrue(practiceItem.evidenceIds.contains("evt-practice-2"))

            val certItem = decision.snapshot.items.first { it.key == "certification:EQUIPMENT_OPERATION" }
            assertTrue(certItem.evidenceIds.contains("evt-cert-issue"))

            val projectItem = decision.snapshot.items.first { it.key == "projects" }
            assertTrue(projectItem.evidenceIds.contains("evt-project-rec"))
            assertTrue(projectItem.evidenceIds.contains("evt-project-acc"))
        }
    }
}
