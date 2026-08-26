@file:UseSerializers(LocalDateSerializer::class, OffsetDateTimeSerializer::class)

package com.example.dualteacher.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate
import java.time.OffsetDateTime

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("OffsetDateTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: OffsetDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
}

// ---------- 事件摄入 ----------

@Serializable
enum class EventType {
    @SerialName("TEACHER_REGISTERED") TEACHER_REGISTERED,
    @SerialName("ENTERPRISE_PRACTICE_RECORDED") ENTERPRISE_PRACTICE_RECORDED,
    @SerialName("CERTIFICATION_ISSUED") CERTIFICATION_ISSUED,
    @SerialName("CERTIFICATION_REVOKED") CERTIFICATION_REVOKED,
    @SerialName("INDUSTRY_PROJECT_RECORDED") INDUSTRY_PROJECT_RECORDED,
    @SerialName("COURSE_MATRIX_PUBLISHED") COURSE_MATRIX_PUBLISHED,
}

@Serializable
data class EventRequest(
    @SerialName("event_id") val eventId: String,
    @SerialName("event_type") val eventType: EventType,
    @SerialName("occurred_at") val occurredAt: OffsetDateTime? = null,
    val payload: JsonObject,
)

@Serializable
data class TeacherRegisteredPayload(
    @SerialName("teacher_id") val teacherId: String,
    val name: String,
)

@Serializable
data class PracticePayload(
    @SerialName("teacher_id") val teacherId: String,
    val company: String,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate,
)

@Serializable
data class CertificationIssuedPayload(
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("cert_code") val certCode: String,
    @SerialName("issued_date") val issuedDate: LocalDate,
    @SerialName("expiry_date") val expiryDate: LocalDate? = null,
)

@Serializable
data class CertificationRevokedPayload(
    @SerialName("cert_code") val certCode: String,
    @SerialName("effective_date") val effectiveDate: LocalDate,
    val reason: String? = null,
)

@Serializable
data class IndustryProjectPayload(
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("project_name") val projectName: String,
    val role: String,
    @SerialName("accepted_by_enterprise") val acceptedByEnterprise: Boolean = false,
    @SerialName("acceptance_date") val acceptanceDate: LocalDate? = null,
)

@Serializable
data class CourseMatrixPayload(
    @SerialName("course_id") val courseId: String,
    val version: Int,
    @SerialName("effective_from") val effectiveFrom: LocalDate,
    val requirements: Requirements,
)

@Serializable
data class IngestResponse(
    @SerialName("event_id") val eventId: String,
    val status: String, // recorded | duplicate
)

// ---------- 课程能力矩阵 ----------

@Serializable
data class Requirements(
    @SerialName("min_practice_days") val minPracticeDays: Int = 0,
    @SerialName("required_certifications") val requiredCertifications: List<String> = emptyList(),
    @SerialName("required_project_roles") val requiredProjectRoles: List<String> = emptyList(),
)

// ---------- 资格判定 ----------

@Serializable
data class DecisionItem(
    val type: String,               // practice | certification | project_role
    val subject: String,            // enterprise_practice | 认证代码 | 项目角色
    val status: String,             // PASS | FAIL
    val required: String,
    val actual: String? = null,
    @SerialName("reason_code") val reasonCode: String? = null,
    val missing: String? = null,
    @SerialName("evidence_ids") val evidenceIds: List<String> = emptyList(),
)

@Serializable
data class DecisionResponse(
    @SerialName("decision_id") val decisionId: String,
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("course_id") val courseId: String,
    @SerialName("as_of") val asOf: LocalDate,
    @SerialName("matrix_version") val matrixVersion: Int,
    val qualified: Boolean,
    val items: List<DecisionItem>,
    @SerialName("created_at") val createdAt: String,
    /** 本次调用是否新建了快照（false 表示返回了既有不可变快照） */
    val created: Boolean = false,
)

// ---------- 教师档案（证据清单） ----------

@Serializable
data class PracticeEvidence(
    val id: String,
    @SerialName("event_id") val eventId: String,
    val company: String,
    @SerialName("start_date") val startDate: LocalDate,
    @SerialName("end_date") val endDate: LocalDate,
)

@Serializable
data class CertificationEvidence(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("cert_code") val certCode: String,
    @SerialName("issued_date") val issuedDate: LocalDate,
    @SerialName("expiry_date") val expiryDate: LocalDate? = null,
)

@Serializable
data class RevocationEvidence(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("cert_code") val certCode: String,
    @SerialName("effective_date") val effectiveDate: LocalDate,
    val reason: String? = null,
)

@Serializable
data class ProjectEvidence(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("project_name") val projectName: String,
    val role: String,
    @SerialName("accepted_by_enterprise") val acceptedByEnterprise: Boolean,
    @SerialName("acceptance_date") val acceptanceDate: LocalDate? = null,
)

@Serializable
data class PortfolioResponse(
    @SerialName("teacher_id") val teacherId: String,
    val practices: List<PracticeEvidence>,
    val certifications: List<CertificationEvidence>,
    val revocations: List<RevocationEvidence>,
    val projects: List<ProjectEvidence>,
)

// ---------- 通用 ----------

@Serializable
data class HealthResponse(val status: String)

@Serializable
data class ErrorResponse(val error: String)
