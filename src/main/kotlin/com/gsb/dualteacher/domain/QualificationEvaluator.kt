package com.gsb.dualteacher.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.LocalDate
import java.util.UUID

/**
 * 资格评估核心：纯函数、无 I/O。
 *
 * 投影规则（与事件到达顺序无关，只看业务发生时间）：
 *  - 只纳入 occurredAt 的业务日期 <= asOf 的事件；
 *  - 企业实践：重叠区间合并后计天，未开始的区间丢弃、跨越 asOf 的截断；
 *  - 认证：asOf 当日必须已签发、未到期（到期日当天仍有效）、且未被已生效的吊销覆盖
 *    （吊销生效日当天即失效；吊销事件可能乱序先到，按 effectiveOn 判断）；
 *  - 产业项目：只有企业验收事件生效（acceptedOn <= asOf）才算证据；
 *  - 能力矩阵：取 effectiveFrom <= asOf 的最新版本。
 */
object QualificationEvaluator {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 把事件流投影成 asOf 当日的世界状态。重复 event_id 只计一次（幂等重试）。 */
    fun project(events: List<StoredEvent>, asOf: LocalDate): ProjectedState {
        val visible = events
            .asSequence()
            .filter { !it.occurredOn().isAfter(asOf) }
            .distinctBy { it.eventId }
            .sortedBy { it.occurredAt }
            .toList()

        val teachers = LinkedHashMap<String, String>()
        val practices = mutableListOf<PracticeInterval>()
        val issuances = LinkedHashMap<String, CertState>()
        val revocations = HashMap<String, Pair<LocalDate, UUID>>()
        val projects = LinkedHashMap<String, ProjectState>()
        val matrices = mutableListOf<MatrixRequirement>()

        for (event in visible) {
            when (event.eventType) {
                EventTypes.TEACHER_REGISTERED -> {
                    val p = json.decodeFromJsonElement<TeacherRegisteredPayload>(event.payload)
                    teachers[p.teacherId] = p.name
                }

                EventTypes.PRACTICE_RECORDED -> {
                    val p = json.decodeFromJsonElement<PracticePayload>(event.payload)
                    practices.add(
                        PracticeInterval(
                            eventId = event.eventId,
                            teacherId = p.teacherId,
                            start = LocalDate.parse(p.startDate),
                            end = LocalDate.parse(p.endDate),
                            enterprise = p.enterprise,
                        ),
                    )
                }

                EventTypes.CERT_ISSUED -> {
                    val p = json.decodeFromJsonElement<CertIssuedPayload>(event.payload)
                    issuances[p.certId] = CertState(
                        certId = p.certId,
                        certType = p.certType,
                        teacherId = p.teacherId,
                        issuedOn = LocalDate.parse(p.issuedOn),
                        expiresOn = LocalDate.parse(p.expiresOn),
                        issuanceEventId = event.eventId,
                    )
                }

                EventTypes.CERT_REVOKED -> {
                    val p = json.decodeFromJsonElement<CertRevokedPayload>(event.payload)
                    val effective = LocalDate.parse(p.effectiveOn)
                    val prev = revocations[p.certId]
                    if (prev == null || effective.isBefore(prev.first)) {
                        revocations[p.certId] = effective to event.eventId
                    }
                }

                EventTypes.PROJECT_ROLE_ASSIGNED -> {
                    val p = json.decodeFromJsonElement<ProjectAssignedPayload>(event.payload)
                    val prev = projects[p.projectId]
                    projects[p.projectId] = ProjectState(
                        projectId = p.projectId,
                        teacherId = p.teacherId,
                        role = p.role,
                        enterprise = p.enterprise,
                        assignedEventId = event.eventId,
                        acceptedOn = prev?.acceptedOn,
                        acceptanceEventId = prev?.acceptanceEventId,
                    )
                }

                EventTypes.PROJECT_ACCEPTED -> {
                    val p = json.decodeFromJsonElement<ProjectAcceptedPayload>(event.payload)
                    val prev = projects[p.projectId]
                    projects[p.projectId] = ProjectState(
                        projectId = p.projectId,
                        teacherId = p.teacherId,
                        role = prev?.role,
                        enterprise = prev?.enterprise,
                        assignedEventId = prev?.assignedEventId,
                        acceptedOn = LocalDate.parse(p.acceptedOn),
                        acceptanceEventId = event.eventId,
                    )
                }

                EventTypes.MATRIX_PUBLISHED -> {
                    val p = json.decodeFromJsonElement<MatrixPayload>(event.payload)
                    matrices.add(
                        MatrixRequirement(
                            version = p.version,
                            courseCode = p.courseCode,
                            courseName = p.courseName,
                            effectiveFrom = LocalDate.parse(p.effectiveFrom),
                            requiredPracticeDays = p.requiredPracticeDays,
                            requiredCertTypes = p.requiredCertTypes,
                            requiredAcceptedProjects = p.requiredAcceptedProjects,
                            eventId = event.eventId,
                        ),
                    )
                }
            }
        }

        val certs = issuances.mapValues { (certId, cert) ->
            revocations[certId]?.let { (revokedOn, revEventId) ->
                cert.copy(revokedOn = revokedOn, revocationEventId = revEventId)
            } ?: cert
        }.values.toList()

        return ProjectedState(
            teachers = teachers,
            practices = practices,
            certs = certs,
            projects = projects.values.toList(),
            matrices = matrices,
        )
    }

    data class Evaluation(
        val matrixVersion: String?,
        val result: String,
        val items: List<DecisionItem>,
    )

    /** 依据投影状态逐项核查，返回总体结论与每一项的证据/缺口。 */
    fun evaluate(state: ProjectedState, teacherId: String, courseCode: String, asOf: LocalDate): Evaluation {
        val items = mutableListOf<DecisionItem>()

        val matrix = state.matrices
            .filter { it.courseCode == courseCode && !it.effectiveFrom.isAfter(asOf) }
            .maxWithOrNull(compareBy<MatrixRequirement> { it.effectiveFrom }.thenBy { it.version })

        if (matrix == null) {
            items += DecisionItem(
                code = "MATRIX",
                status = ItemStatus.FAIL,
                message = "课程 $courseCode 在 $asOf 没有已生效的能力矩阵版本",
                missing = "缺少生效的课程能力矩阵",
            )
            return Evaluation(null, QualificationResult.NOT_QUALIFIED, items)
        }

        items += DecisionItem(
            code = "MATRIX",
            status = ItemStatus.PASS,
            message = "采用能力矩阵版本 ${matrix.version}（${matrix.effectiveFrom} 起生效）",
            evidenceIds = listOf(matrix.eventId.toString()),
        )

        items += evaluatePractice(state, teacherId, asOf, matrix.requiredPracticeDays)
        items += matrix.requiredCertTypes.map { evaluateCert(state, teacherId, it, asOf) }
        items += evaluateProjects(state, teacherId, asOf, matrix.requiredAcceptedProjects)

        val result = if (items.all { it.status == ItemStatus.PASS }) {
            QualificationResult.QUALIFIED
        } else {
            QualificationResult.NOT_QUALIFIED
        }
        return Evaluation(matrix.version, result, items)
    }

    private fun evaluatePractice(
        state: ProjectedState,
        teacherId: String,
        asOf: LocalDate,
        requiredDays: Long,
    ): DecisionItem {
        val own = state.practices.filter { it.teacherId == teacherId && !it.start.isAfter(asOf) }
        val ranges = clipToAsOf(own.map { DateRange(it.start, it.end) }, asOf)
        val merged = mergeRanges(ranges)
        val days = merged.sumOf { it.days() }
        val evidence = own.map { it.eventId.toString() }

        return if (days >= requiredDays) {
            DecisionItem(
                code = "PRACTICE",
                status = ItemStatus.PASS,
                message = "企业实践合并后共 $days 天（${merged.size} 段，重叠区间只计一次），要求 $requiredDays 天",
                evidenceIds = evidence,
            )
        } else {
            DecisionItem(
                code = "PRACTICE",
                status = ItemStatus.FAIL,
                message = "企业实践合并后共 $days 天，要求 $requiredDays 天",
                evidenceIds = evidence,
                missing = "企业实践还差 ${requiredDays - days} 天",
            )
        }
    }

    private fun evaluateCert(
        state: ProjectedState,
        teacherId: String,
        certType: String,
        asOf: LocalDate,
    ): DecisionItem {
        val code = "CERT_$certType"
        val candidates = state.certs.filter { it.teacherId == teacherId && it.certType == certType }
        val valid = candidates.firstOrNull { it.isValidOn(asOf) }

        if (valid != null) {
            return DecisionItem(
                code = code,
                status = ItemStatus.PASS,
                message = "设备认证 $certType 有效：证书 ${valid.certId}，有效期至 ${valid.expiresOn}",
                evidenceIds = listOf(valid.issuanceEventId.toString()),
            )
        }

        val issued = candidates.filter { !it.issuedOn.isAfter(asOf) }
        val revoked = issued.firstOrNull { it.revokedOn != null && !it.revokedOn.isAfter(asOf) }
        if (revoked != null) {
            return DecisionItem(
                code = code,
                status = ItemStatus.FAIL,
                message = "证书 ${revoked.certId} 已被吊销，吊销自 ${revoked.revokedOn} 起生效",
                evidenceIds = listOfNotNull(
                    revoked.issuanceEventId.toString(),
                    revoked.revocationEventId?.toString(),
                ),
                missing = "缺少 $certType 类别的有效设备认证（现有证书已吊销）",
            )
        }
        val expired = issued.filter { it.expiresOn.isBefore(asOf) }
        if (expired.isNotEmpty()) {
            val oldest = expired.minByOrNull { it.expiresOn }!!
            return DecisionItem(
                code = code,
                status = ItemStatus.FAIL,
                message = "证书 ${oldest.certId} 已于 ${oldest.expiresOn} 到期",
                evidenceIds = listOf(oldest.issuanceEventId.toString()),
                missing = "缺少 $certType 类别的有效设备认证（现有证书已过期）",
            )
        }
        return DecisionItem(
            code = code,
            status = ItemStatus.FAIL,
            message = "未找到 $certType 类别的设备认证签发记录",
            missing = "缺少 $certType 类别的设备认证",
        )
    }

    private fun evaluateProjects(
        state: ProjectedState,
        teacherId: String,
        asOf: LocalDate,
        requiredCount: Int,
    ): DecisionItem {
        val own = state.projects.filter { it.teacherId == teacherId }
        val accepted = own.filter { it.isAcceptedBy(asOf) }
        val evidence = accepted.flatMap {
            listOfNotNull(it.assignedEventId?.toString(), it.acceptanceEventId?.toString())
        }
        val pending = own.filter { !it.isAcceptedBy(asOf) }

        return if (accepted.size >= requiredCount) {
            DecisionItem(
                code = "PROJECT",
                status = ItemStatus.PASS,
                message = "已通过企业验收的产业项目 ${accepted.size} 个，要求 $requiredCount 个",
                evidenceIds = evidence,
            )
        } else {
            val pendingNote = if (pending.isNotEmpty()) {
                "；另有 ${pending.size} 个项目尚未通过企业验收，不计入证据"
            } else ""
            DecisionItem(
                code = "PROJECT",
                status = ItemStatus.FAIL,
                message = "已通过企业验收的产业项目 ${accepted.size} 个，要求 $requiredCount 个$pendingNote",
                evidenceIds = evidence,
                missing = "还差 ${requiredCount - accepted.size} 个经企业验收的产业项目",
            )
        }
    }
}
