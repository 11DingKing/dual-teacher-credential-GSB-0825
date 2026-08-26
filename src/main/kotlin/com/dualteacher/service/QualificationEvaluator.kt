package com.dualteacher.service

import com.dualteacher.domain.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class MergedInterval(
    val start: LocalDate,
    val end: LocalDate,
    val sourceIntervalIds: List<String>,
    val sourceEventIds: List<String>
) {
    val days: Long
        get() = ChronoUnit.DAYS.between(start, end) + 1
}

object IntervalMerger {

    fun merge(
        intervals: List<PracticeInterval>,
        asOf: LocalDate
    ): List<MergedInterval> {
        val clamped = intervals
            .filter { !it.startDate.isAfter(asOf) }
            .map { interval ->
                val end = minOf(interval.endDate, asOf)
                MergedInterval(
                    start = interval.startDate,
                    end = end,
                    sourceIntervalIds = listOf(interval.intervalId),
                    sourceEventIds = listOf(interval.eventId)
                )
            }
            .filter { !it.end.isBefore(it.start) }
            .sortedBy { it.start }

        if (clamped.isEmpty()) return emptyList()

        val merged = mutableListOf<MergedInterval>()
        var current = clamped.first()

        for (next in clamped.drop(1)) {
            if (!next.start.isAfter(current.end)) {
                current = MergedInterval(
                    start = current.start,
                    end = maxOf(current.end, next.end),
                    sourceIntervalIds = current.sourceIntervalIds + next.sourceIntervalIds,
                    sourceEventIds = current.sourceEventIds + next.sourceEventIds
                )
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        return merged
    }

    fun totalDays(merged: List<MergedInterval>): Long =
        merged.sumOf { it.days }
}

class QualificationEvaluator {

    fun evaluate(
        teacherState: ProjectedState,
        courseMatrices: List<CapabilityMatrix>,
        teacherId: String,
        courseId: String,
        asOf: LocalDate
    ): QualificationResult {
        val matrix = selectMatrix(courseMatrices, asOf)

        if (matrix == null) {
            return QualificationResult(
                teacherId = teacherId,
                courseId = courseId,
                asOf = asOf,
                overallResult = QualificationStatus.FAIL,
                items = listOf(
                    RequirementItem(
                        key = "matrix",
                        label = "课程能力矩阵",
                        status = QualificationStatus.FAIL,
                        required = "存在生效的能力矩阵",
                        actual = "未找到",
                        evidenceIds = emptyList(),
                        missing = "课程 $courseId 在 $asOf 前没有发布能力矩阵"
                    )
                )
            )
        }

        val items = mutableListOf<RequirementItem>()

        items.add(evaluatePractice(teacherState, asOf, matrix.requirements.minPracticeDays))
        items.addAll(evaluateCertifications(teacherState, asOf, matrix.requirements.requiredCertTypes))
        items.add(evaluateProjects(teacherState, asOf, matrix.requirements.minAcceptedProjects))

        val overall = if (items.all { it.status == QualificationStatus.PASS }) {
            QualificationStatus.PASS
        } else {
            QualificationStatus.FAIL
        }

        return QualificationResult(
            teacherId = teacherId,
            courseId = courseId,
            asOf = asOf,
            overallResult = overall,
            items = items
        )
    }

    private fun selectMatrix(
        matrices: List<CapabilityMatrix>,
        asOf: LocalDate
    ): CapabilityMatrix? {
        return matrices
            .filter { !it.effectiveDate.isAfter(asOf) }
            .maxByOrNull { it.effectiveDate }
    }

    private fun evaluatePractice(
        state: ProjectedState,
        asOf: LocalDate,
        requiredDays: Int
    ): RequirementItem {
        val merged = IntervalMerger.merge(state.practiceIntervals, asOf)
        val actualDays = IntervalMerger.totalDays(merged)
        val evidenceIds = merged.flatMap { it.sourceEventIds }.distinct()

        return if (actualDays >= requiredDays) {
            RequirementItem(
                key = "practice",
                label = "企业实践时长",
                status = QualificationStatus.PASS,
                required = "≥ $requiredDays 天",
                actual = "$actualDays 天",
                evidenceIds = evidenceIds,
                missing = null
            )
        } else {
            RequirementItem(
                key = "practice",
                label = "企业实践时长",
                status = QualificationStatus.FAIL,
                required = "≥ $requiredDays 天",
                actual = "$actualDays 天",
                evidenceIds = evidenceIds,
                missing = "企业实践时长不足，差 ${requiredDays - actualDays} 天"
            )
        }
    }

    private fun evaluateCertifications(
        state: ProjectedState,
        asOf: LocalDate,
        requiredTypes: List<String>
    ): List<RequirementItem> {
        return requiredTypes.map { certType ->
            val validCerts = state.certifications
                .filter { it.certType == certType && it.isValidOn(asOf) }

            if (validCerts.isNotEmpty()) {
                val cert = validCerts.first()
                val evidenceIds = listOfNotNull(cert.issueEventId, cert.revokeEventId)
                RequirementItem(
                    key = "certification:$certType",
                    label = "认证: $certType",
                    status = QualificationStatus.PASS,
                    required = "持有有效认证",
                    actual = "证书 ${cert.certificationId} (有效期至 ${cert.expiryDate})",
                    evidenceIds = evidenceIds,
                    missing = null
                )
            } else {
                val anyCert = state.certifications.firstOrNull { it.certType == certType }
                val reason = when {
                    anyCert == null -> "无该类型认证记录"
                    asOf.isBefore(anyCert.issuedDate) -> "认证尚未签发"
                    asOf.isAfter(anyCert.expiryDate) -> "认证已于 ${anyCert.expiryDate} 过期"
                    anyCert.revokedAt != null && !asOf.isBefore(anyCert.revokedAt) ->
                        "认证已于 ${anyCert.revokedAt} 被吊销"
                    else -> "认证状态无效"
                }
                RequirementItem(
                    key = "certification:$certType",
                    label = "认证: $certType",
                    status = QualificationStatus.FAIL,
                    required = "持有有效认证",
                    actual = reason,
                    evidenceIds = listOfNotNull(anyCert?.issueEventId, anyCert?.revokeEventId),
                    missing = reason
                )
            }
        }
    }

    private fun evaluateProjects(
        state: ProjectedState,
        asOf: LocalDate,
        requiredCount: Int
    ): RequirementItem {
        val acceptedProjects = state.projects.filter { it.isAcceptedBy(asOf) }
        val actualCount = acceptedProjects.size
        val evidenceIds = acceptedProjects.flatMap { project ->
            listOfNotNull(project.recordEventId, project.acceptEventId)
        }

        return if (actualCount >= requiredCount) {
            RequirementItem(
                key = "projects",
                label = "产业项目经历",
                status = QualificationStatus.PASS,
                required = "≥ $requiredCount 个验收项目",
                actual = "$actualCount 个已验收",
                evidenceIds = evidenceIds,
                missing = null
            )
        } else {
            RequirementItem(
                key = "projects",
                label = "产业项目经历",
                status = QualificationStatus.FAIL,
                required = "≥ $requiredCount 个验收项目",
                actual = "$actualCount 个已验收",
                evidenceIds = evidenceIds,
                missing = "已验收产业项目不足，差 ${requiredCount - actualCount} 个"
            )
        }
    }
}
