package com.gsb.dualteacher

import com.gsb.dualteacher.domain.CertIssuedPayload
import com.gsb.dualteacher.domain.CertRevokedPayload
import com.gsb.dualteacher.domain.EventEnvelope
import com.gsb.dualteacher.domain.EventTypes
import com.gsb.dualteacher.domain.MatrixPayload
import com.gsb.dualteacher.domain.PracticePayload
import com.gsb.dualteacher.domain.ProjectAcceptedPayload
import com.gsb.dualteacher.domain.ProjectAssignedPayload
import com.gsb.dualteacher.domain.StoredEvent
import com.gsb.dualteacher.domain.TeacherRegisteredPayload
import com.gsb.dualteacher.domain.QualificationEvaluator
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * 测试数据构造：T1 时间线
 *  - 两段重叠企业实践：2025-01-01~2025-03-01 与 2025-02-15~2025-04-01（合并 91 天）
 *  - 设备认证 C1：2025-06-01 签发，2026-06-30 到期
 *  - 吊销事件先到达（业务发生 2026-04-20），生效日却是 2026-05-01
 *  - 产业项目 P1：2025-09-01 经企业验收
 *  - 能力矩阵 v1：2025-01-01 起生效，要求 90 天实践 / EQUIPMENT 认证 / 1 个验收项目
 */
object Fixtures {

    const val T1 = "T1"
    const val COURSE = "HRP-101"
    const val CERT_TYPE = "EQUIPMENT"

    val ID_TEACHER = UUID.fromString("11111111-1111-4111-8111-111111111111")
    val ID_PRACTICE_1 = UUID.fromString("22222222-2222-4222-8222-222222222222")
    val ID_PRACTICE_2 = UUID.fromString("33333333-3333-4333-8333-333333333333")
    val ID_CERT_ISSUED = UUID.fromString("44444444-4444-4444-8444-444444444444")
    val ID_CERT_REVOKED = UUID.fromString("55555555-5555-4555-8555-555555555555")
    val ID_PROJECT_ASSIGNED = UUID.fromString("66666666-6666-4666-8666-666666666666")
    val ID_PROJECT_ACCEPTED = UUID.fromString("77777777-7777-4777-8777-777777777777")
    val ID_MATRIX_V1 = UUID.fromString("88888888-8888-4888-8888-888888888888")

    fun instant(date: String): Instant =
        LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant()

    fun teacherRegistered(
        id: UUID = ID_TEACHER,
        at: String = "2024-12-01",
        teacher: String = T1,
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.TEACHER_REGISTERED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            TeacherRegisteredPayload.serializer(),
            TeacherRegisteredPayload(teacher, "张老师"),
        ) as JsonObject,
    )

    fun practice(
        id: UUID,
        at: String,
        teacher: String,
        start: String,
        end: String,
        enterprise: String = "华东数控有限公司",
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.PRACTICE_RECORDED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            PracticePayload.serializer(),
            PracticePayload(teacher, start, end, enterprise),
        ) as JsonObject,
    )

    fun certIssued(
        id: UUID,
        at: String,
        teacher: String,
        certId: String = "C1",
        certType: String = CERT_TYPE,
        issuedOn: String = "2025-06-01",
        expiresOn: String = "2026-06-30",
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.CERT_ISSUED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            CertIssuedPayload.serializer(),
            CertIssuedPayload(teacher, certId, certType, issuedOn, expiresOn, "装备制造行业协会"),
        ) as JsonObject,
    )

    fun certRevoked(
        id: UUID,
        at: String,
        teacher: String,
        certId: String = "C1",
        effectiveOn: String = "2026-05-01",
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.CERT_REVOKED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            CertRevokedPayload.serializer(),
            CertRevokedPayload(teacher, certId, effectiveOn, "设备安全抽查不合格"),
        ) as JsonObject,
    )

    fun projectAssigned(
        id: UUID,
        at: String,
        teacher: String,
        projectId: String = "P1",
        role: String = "技术顾问",
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.PROJECT_ROLE_ASSIGNED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            ProjectAssignedPayload.serializer(),
            ProjectAssignedPayload(teacher, projectId, role, "产线智能化改造", "华东数控有限公司"),
        ) as JsonObject,
    )

    fun projectAccepted(
        id: UUID,
        at: String,
        teacher: String,
        projectId: String = "P1",
        acceptedOn: String = "2025-09-01",
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.PROJECT_ACCEPTED,
        teacher,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            ProjectAcceptedPayload.serializer(),
            ProjectAcceptedPayload(teacher, projectId, acceptedOn),
        ) as JsonObject,
    )

    fun matrix(
        id: UUID,
        at: String,
        version: String,
        course: String = COURSE,
        effectiveFrom: String,
        practiceDays: Long,
        certTypes: List<String> = listOf(CERT_TYPE),
        projects: Int = 1,
    ): StoredEvent = StoredEvent(
        id,
        EventTypes.MATRIX_PUBLISHED,
        null,
        instant(at),
        QualificationEvaluator.json.encodeToJsonElement(
            MatrixPayload.serializer(),
            MatrixPayload(version, course, "高风险数控加工实践", effectiveFrom, practiceDays, certTypes, projects),
        ) as JsonObject,
    )

    /** T1 完整事件时间线，故意乱序排列（吊销先于签发、验收先于委派）。 */
    fun t1Timeline(): List<StoredEvent> = listOf(
        matrix(ID_MATRIX_V1, at = "2024-12-15", version = "v1", effectiveFrom = "2025-01-01", practiceDays = 90),
        certRevoked(ID_CERT_REVOKED, at = "2026-04-20", teacher = T1),
        teacherRegistered(at = "2024-12-01"),
        practice(ID_PRACTICE_2, at = "2025-04-02", teacher = T1, start = "2025-02-15", end = "2025-04-01"),
        projectAccepted(ID_PROJECT_ACCEPTED, at = "2025-09-02", teacher = T1),
        practice(ID_PRACTICE_1, at = "2025-03-02", teacher = T1, start = "2025-01-01", end = "2025-03-01"),
        certIssued(ID_CERT_ISSUED, at = "2025-06-01", teacher = T1),
        projectAssigned(ID_PROJECT_ASSIGNED, at = "2025-08-01", teacher = T1),
    )

    fun envelope(event: StoredEvent): EventEnvelope = EventEnvelope(
        eventId = event.eventId.toString(),
        eventType = event.eventType,
        occurredAt = event.occurredAt.toString(),
        teacherId = event.teacherId,
        payload = event.payload,
    )
}
