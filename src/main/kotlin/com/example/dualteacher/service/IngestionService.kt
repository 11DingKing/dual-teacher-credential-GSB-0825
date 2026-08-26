package com.example.dualteacher.service

import com.example.dualteacher.db.CertificationRevocations
import com.example.dualteacher.db.Certifications
import com.example.dualteacher.db.CourseMatrices
import com.example.dualteacher.db.EnterprisePractices
import com.example.dualteacher.db.Events
import com.example.dualteacher.db.IndustryProjects
import com.example.dualteacher.db.Teachers
import com.example.dualteacher.domain.CertificationIssuedPayload
import com.example.dualteacher.domain.CertificationRevokedPayload
import com.example.dualteacher.domain.CourseMatrixPayload
import com.example.dualteacher.domain.EventRequest
import com.example.dualteacher.domain.EventType
import com.example.dualteacher.domain.IndustryProjectPayload
import com.example.dualteacher.domain.IngestResponse
import com.example.dualteacher.domain.PracticePayload
import com.example.dualteacher.domain.TeacherRegisteredPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 事件摄入服务。
 * - 幂等：events.event_id 主键 + ON CONFLICT DO NOTHING，重试返回 duplicate，投影不会重复写入；
 * - 乱序：事件与投影在同一事务写入，投影行只携带业务时间字段，
 *   评估在查询时按业务时间进行，与事件到达顺序无关（例如吊销可先于签发到达）。
 */
class IngestionService(private val db: Database) {

    private val json = Json { ignoreUnknownKeys = true }

    fun ingest(request: EventRequest): IngestResponse = transaction(db) {
        require(request.eventId.isNotBlank()) { "event_id must not be blank" }

        val inserted = Events.insertIgnore {
            it[eventId] = request.eventId
            it[eventType] = request.eventType.name
            it[occurredAt] = request.occurredAt
            it[payload] = request.payload
            it[receivedAt] = OffsetDateTime.now()
        }
        if (inserted.insertedCount == 0) {
            return@transaction IngestResponse(request.eventId, "duplicate")
        }

        when (request.eventType) {
            EventType.TEACHER_REGISTERED -> applyTeacher(request)
            EventType.ENTERPRISE_PRACTICE_RECORDED -> applyPractice(request)
            EventType.CERTIFICATION_ISSUED -> applyCertification(request)
            EventType.CERTIFICATION_REVOKED -> applyRevocation(request)
            EventType.INDUSTRY_PROJECT_RECORDED -> applyProject(request)
            EventType.COURSE_MATRIX_PUBLISHED -> applyMatrix(request)
        }
        IngestResponse(request.eventId, "recorded")
    }

    private fun applyTeacher(request: EventRequest) {
        val payload = decode<TeacherRegisteredPayload>(request.payload)
        require(payload.teacherId.isNotBlank()) { "teacher_id must not be blank" }
        require(payload.name.isNotBlank()) { "name must not be blank" }
        // 同一教师的重复注册事件（不同 event_id）忽略，保留首条
        Teachers.insertIgnore {
            it[teacherId] = payload.teacherId
            it[name] = payload.name
            it[eventId] = request.eventId
            it[createdAt] = OffsetDateTime.now()
        }
    }

    private fun applyPractice(request: EventRequest) {
        val payload = decode<PracticePayload>(request.payload)
        requireTeacherExists(payload.teacherId)
        require(!payload.endDate.isBefore(payload.startDate)) { "end_date must not be before start_date" }
        EnterprisePractices.insertIgnore {
            it[id] = UUID.randomUUID()
            it[teacherId] = payload.teacherId
            it[company] = payload.company
            it[startDate] = payload.startDate
            it[endDate] = payload.endDate
            it[eventId] = request.eventId
        }
    }

    private fun applyCertification(request: EventRequest) {
        val payload = decode<CertificationIssuedPayload>(request.payload)
        requireTeacherExists(payload.teacherId)
        require(payload.certCode.isNotBlank()) { "cert_code must not be blank" }
        Certifications.insertIgnore {
            it[id] = UUID.randomUUID()
            it[teacherId] = payload.teacherId
            it[certCode] = payload.certCode
            it[issuedDate] = payload.issuedDate
            it[expiryDate] = payload.expiryDate
            it[eventId] = request.eventId
        }
    }

    private fun applyRevocation(request: EventRequest) {
        val payload = decode<CertificationRevokedPayload>(request.payload)
        require(payload.certCode.isNotBlank()) { "cert_code must not be blank" }
        // 注意：不校验证书是否已签发——吊销允许先于签发到达，评估时按业务时间关联
        CertificationRevocations.insertIgnore {
            it[id] = UUID.randomUUID()
            it[certCode] = payload.certCode
            it[effectiveDate] = payload.effectiveDate
            it[reason] = payload.reason
            it[eventId] = request.eventId
        }
    }

    private fun applyProject(request: EventRequest) {
        val payload = decode<IndustryProjectPayload>(request.payload)
        requireTeacherExists(payload.teacherId)
        require(payload.role.isNotBlank()) { "role must not be blank" }
        IndustryProjects.insertIgnore {
            it[id] = UUID.randomUUID()
            it[teacherId] = payload.teacherId
            it[projectName] = payload.projectName
            it[role] = payload.role
            it[acceptedByEnterprise] = payload.acceptedByEnterprise
            it[acceptanceDate] = payload.acceptanceDate
            it[eventId] = request.eventId
        }
    }

    private fun applyMatrix(request: EventRequest) {
        val payload = decode<CourseMatrixPayload>(request.payload)
        require(payload.courseId.isNotBlank()) { "course_id must not be blank" }
        require(payload.version >= 1) { "version must be >= 1" }
        require(payload.requirements.minPracticeDays >= 0) { "min_practice_days must be >= 0" }
        // 同一 (course_id, version) 重复发布忽略，保留首条
        CourseMatrices.insertIgnore {
            it[id] = UUID.randomUUID()
            it[courseId] = payload.courseId
            it[version] = payload.version
            it[effectiveFrom] = payload.effectiveFrom
            it[requirements] = payload.requirements
            it[eventId] = request.eventId
        }
    }

    private fun requireTeacherExists(teacherId: String) {
        val exists = Teachers.select(Teachers.teacherId).where { Teachers.teacherId eq teacherId }.count() > 0
        require(exists) { "unknown teacher: $teacherId (teacher must be registered first)" }
    }

    private inline fun <reified T> decode(payload: JsonObject): T =
        try {
            json.decodeFromJsonElement<T>(payload)
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid payload for event: ${e.message}")
        }
}
