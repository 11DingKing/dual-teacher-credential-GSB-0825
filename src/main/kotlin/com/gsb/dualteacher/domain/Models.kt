package com.gsb.dualteacher.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 来源事件类型。所有事件都带全局唯一 event_id，按业务发生时间 occurredAt 投影，
 * 与服务器收到的先后顺序无关。
 */
object EventTypes {
    const val TEACHER_REGISTERED = "teacher.registered"
    const val PRACTICE_RECORDED = "practice.recorded"
    const val CERT_ISSUED = "cert.issued"
    const val CERT_REVOKED = "cert.revoked"
    const val PROJECT_ROLE_ASSIGNED = "project.role.assigned"
    const val PROJECT_ACCEPTED = "project.accepted"
    const val MATRIX_PUBLISHED = "matrix.published"

    val ALL: Set<String> = setOf(
        TEACHER_REGISTERED,
        PRACTICE_RECORDED,
        CERT_ISSUED,
        CERT_REVOKED,
        PROJECT_ROLE_ASSIGNED,
        PROJECT_ACCEPTED,
        MATRIX_PUBLISHED,
    )
}

/** 事件接入信封：eventId 全局唯一（幂等键），occurredAt 为业务发生时间。 */
@Serializable
data class EventEnvelope(
    val eventId: String,
    val eventType: String,
    val occurredAt: String,
    val teacherId: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class TeacherRegisteredPayload(
    val teacherId: String,
    val name: String,
)

/** 企业实践区间（闭区间，含首尾两天）。 */
@Serializable
data class PracticePayload(
    val teacherId: String,
    val startDate: String,
    val endDate: String,
    val enterprise: String? = null,
)

/** 认证签发：issuedOn 起生效，expiresOn 当日仍有效。 */
@Serializable
data class CertIssuedPayload(
    val teacherId: String,
    val certId: String,
    val certType: String,
    val issuedOn: String,
    val expiresOn: String,
    val issuingBody: String? = null,
)

/** 认证吊销：effectiveOn 当日起证书失效（吊销事件可能先于签发事件到达）。 */
@Serializable
data class CertRevokedPayload(
    val teacherId: String,
    val certId: String,
    val effectiveOn: String,
    val reason: String? = null,
)

/** 产业项目角色。 */
@Serializable
data class ProjectAssignedPayload(
    val teacherId: String,
    val projectId: String,
    val role: String,
    val projectName: String? = null,
    val enterprise: String? = null,
)

/** 产业项目通过企业验收：只有验收后才算资格证据。 */
@Serializable
data class ProjectAcceptedPayload(
    val teacherId: String,
    val projectId: String,
    val acceptedOn: String,
)

/** 带版本的课程能力矩阵：effectiveOn 起生效（含当日）。 */
@Serializable
data class MatrixPayload(
    val version: String,
    val courseCode: String,
    val courseName: String? = null,
    val effectiveFrom: String,
    val requiredPracticeDays: Long = 0,
    val requiredCertTypes: List<String> = emptyList(),
    val requiredAcceptedProjects: Int = 0,
)

/** 已落库的事件（投影输入）。 */
data class StoredEvent(
    val eventId: UUID,
    val eventType: String,
    val teacherId: String?,
    val occurredAt: Instant,
    val payload: JsonObject,
) {
    fun occurredOn(zoneOffset: ZoneOffset = ZoneOffset.UTC): LocalDate =
        occurredAt.atZone(zoneOffset).toLocalDate()
}

data class PracticeInterval(
    val eventId: UUID,
    val teacherId: String,
    val start: LocalDate,
    val end: LocalDate,
    val enterprise: String?,
)

data class CertState(
    val certId: String,
    val certType: String,
    val teacherId: String,
    val issuedOn: LocalDate,
    val expiresOn: LocalDate,
    val issuanceEventId: UUID,
    val revokedOn: LocalDate? = null,
    val revocationEventId: UUID? = null,
) {
    /** asOf 当日证书有效：已签发、未到期（到期日当天有效）、且未被已生效的吊销覆盖。 */
    fun isValidOn(asOf: LocalDate): Boolean {
        val issued = !issuedOn.isAfter(asOf)
        val notExpired = !expiresOn.isBefore(asOf)
        val notRevoked = revokedOn == null || revokedOn.isAfter(asOf)
        return issued && notExpired && notRevoked
    }
}

data class ProjectState(
    val projectId: String,
    val teacherId: String,
    val role: String?,
    val enterprise: String?,
    val assignedEventId: UUID?,
    val acceptedOn: LocalDate? = null,
    val acceptanceEventId: UUID? = null,
) {
    fun isAcceptedBy(asOf: LocalDate): Boolean =
        acceptedOn != null && !acceptedOn.isAfter(asOf)
}

data class MatrixRequirement(
    val version: String,
    val courseCode: String,
    val courseName: String?,
    val effectiveFrom: LocalDate,
    val requiredPracticeDays: Long,
    val requiredCertTypes: List<String>,
    val requiredAcceptedProjects: Int,
    val eventId: UUID,
)

data class ProjectedState(
    val teachers: Map<String, String>,
    val practices: List<PracticeInterval>,
    val certs: List<CertState>,
    val projects: List<ProjectState>,
    val matrices: List<MatrixRequirement>,
)

/** 逐项核查结论。evidenceIds 采用事件 event_id / 业务证件 ID。 */
@Serializable
data class DecisionItem(
    val code: String,
    val status: String,
    val message: String,
    val evidenceIds: List<String> = emptyList(),
    val missing: String? = null,
)

@Serializable
data class DecisionSnapshot(
    val decisionId: String,
    val teacherId: String,
    val courseCode: String,
    val asOf: String,
    val matrixVersion: String?,
    val result: String,
    val items: List<DecisionItem>,
    val evaluatedAt: String,
)

object QualificationResult {
    const val QUALIFIED = "QUALIFIED"
    const val NOT_QUALIFIED = "NOT_QUALIFIED"
}

object ItemStatus {
    const val PASS = "PASS"
    const val FAIL = "FAIL"
}
