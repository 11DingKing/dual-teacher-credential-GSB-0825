package com.example.dualteacher.service

import com.example.dualteacher.domain.DecisionItem
import com.example.dualteacher.domain.Requirements
import java.time.LocalDate
import java.util.UUID

/** 评估用的只读投影数据（与数据库行解耦，便于单元测试） */
data class Practice(val id: UUID, val startDate: LocalDate, val endDate: LocalDate)
data class Cert(val id: UUID, val code: String, val issued: LocalDate, val expiry: LocalDate?)
data class Revocation(val id: UUID, val code: String, val effective: LocalDate)
data class Project(val id: UUID, val role: String, val accepted: Boolean, val acceptanceDate: LocalDate?)

/**
 * 资格评估器：纯函数。输入是 as_of 之前的全部业务事实（与事件到达顺序无关），
 * 输出逐项结论（PASS/FAIL）、采用的证据 ID 与缺口说明。
 */
object QualificationEvaluator {

    fun evaluate(
        requirements: Requirements,
        practices: List<Practice>,
        certifications: List<Cert>,
        revocations: List<Revocation>,
        projects: List<Project>,
        asOf: LocalDate,
    ): List<DecisionItem> = buildList {
        add(evaluatePractice(requirements.minPracticeDays, practices, asOf))
        requirements.requiredCertifications.forEach { code ->
            add(evaluateCertification(code, certifications, revocations, asOf))
        }
        requirements.requiredProjectRoles.forEach { role ->
            add(evaluateProjectRole(role, projects, asOf))
        }
    }

    /** 企业实践：仅统计 as_of 当天及之前的部分，重叠区间合并后只算一次。 */
    private fun evaluatePractice(minDays: Int, practices: List<Practice>, asOf: LocalDate): DecisionItem {
        val relevant = practices.filter { it.startDate <= asOf }
        val clipped = relevant.map { it.startDate to minOf(it.endDate, asOf) }
        val days = IntervalMerger.totalDays(clipped)
        val pass = days >= minDays.toLong()
        return DecisionItem(
            type = "practice",
            subject = "enterprise_practice",
            status = if (pass) "PASS" else "FAIL",
            required = ">= $minDays merged practice days by $asOf",
            actual = "$days days",
            reasonCode = if (pass) null else "PRACTICE_DAYS_INSUFFICIENT",
            missing = if (pass) null else "only $days merged practice days by $asOf, need at least $minDays",
            evidenceIds = relevant.map { it.id.toString() },
        )
    }

    /**
     * 认证：as_of 当天必须存在已签发且未到期的证书，
     * 且不存在 effective_date <= as_of 的吊销（无论吊销事件何时到达）。
     */
    private fun evaluateCertification(
        code: String,
        certifications: List<Cert>,
        revocations: List<Revocation>,
        asOf: LocalDate,
    ): DecisionItem {
        val issued = certifications.filter { it.code == code && it.issued <= asOf }
        val stillValid = issued.filter { it.expiry == null || it.expiry >= asOf }
        val activeRevocations = revocations.filter { it.code == code && it.effective <= asOf }
        val required = "valid certification $code on $asOf"

        return when {
            issued.isEmpty() -> DecisionItem(
                type = "certification", subject = code, status = "FAIL",
                required = required, actual = "none",
                reasonCode = "CERT_NOT_ISSUED",
                missing = "certification $code has not been issued by $asOf",
                evidenceIds = emptyList(),
            )

            stillValid.isEmpty() -> {
                val latestExpiry = issued.mapNotNull { it.expiry }.maxOrNull()
                val revokedNote = activeRevocations.minByOrNull { it.effective }
                    ?.let { "; also revoked effective ${it.effective}" } ?: ""
                DecisionItem(
                    type = "certification", subject = code, status = "FAIL",
                    required = required, actual = "expired on $latestExpiry",
                    reasonCode = "CERT_EXPIRED",
                    missing = "certification $code expired on $latestExpiry$revokedNote",
                    evidenceIds = issued.map { it.id.toString() } + activeRevocations.map { it.id.toString() },
                )
            }

            activeRevocations.isNotEmpty() -> {
                val latestRevocation = activeRevocations.maxBy { it.effective }
                DecisionItem(
                    type = "certification", subject = code, status = "FAIL",
                    required = required, actual = "revoked effective ${latestRevocation.effective}",
                    reasonCode = "CERT_REVOKED",
                    missing = "certification $code was revoked effective ${latestRevocation.effective}",
                    evidenceIds = stillValid.map { it.id.toString() } + latestRevocation.id.toString(),
                )
            }

            else -> DecisionItem(
                type = "certification", subject = code, status = "PASS",
                required = required, actual = "valid",
                evidenceIds = stillValid.map { it.id.toString() },
            )
        }
    }

    /** 产业项目：必须经过企业验收（验收日期不晚于 as_of）才算证据。 */
    private fun evaluateProjectRole(role: String, projects: List<Project>, asOf: LocalDate): DecisionItem {
        val matching = projects.filter { it.role == role }
        val accepted = matching.filter { it.accepted && (it.acceptanceDate == null || it.acceptanceDate <= asOf) }
        val pass = accepted.isNotEmpty()
        return DecisionItem(
            type = "project_role",
            subject = role,
            status = if (pass) "PASS" else "FAIL",
            required = "enterprise-accepted industry project with role $role by $asOf",
            actual = when {
                pass -> "accepted"
                matching.isEmpty() -> "no project"
                else -> "not accepted by enterprise"
            },
            reasonCode = if (pass) null else "PROJECT_NOT_ACCEPTED",
            missing = if (pass) null else "no enterprise-accepted industry project with role $role by $asOf",
            evidenceIds = (if (pass) accepted else matching).map { it.id.toString() },
        )
    }
}
