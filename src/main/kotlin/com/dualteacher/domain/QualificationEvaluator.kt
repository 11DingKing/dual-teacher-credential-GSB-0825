package com.dualteacher.domain

import java.time.LocalDate

/**
 * Applies the competency matrix effective at `as_of` to a teacher's projected state,
 * producing a per-requirement verdict with the evidence event ids used and, on failure,
 * what is still missing.
 */
object QualificationEvaluator {

    /** Requirement labels, stable so callers/tests can key off them. */
    const val REQ_MATRIX = "competency_matrix"
    const val REQ_PRACTICE = "enterprise_practice"
    const val REQ_CERTIFICATION = "equipment_certification"
    const val REQ_PROJECT = "industry_project"

    /**
     * @param asOf the evaluation baseline date.
     * @return per-item checks; overall qualified is true only when every applicable check passes.
     */
    fun evaluate(courseId: String, asOf: LocalDate, state: ProjectedState): Pair<Boolean, EvaluationOutcome> {
        val matrix = state.matrixVersions
            .filter { !it.effectiveFrom.isAfter(asOf) }
            .maxWithOrNull(compareBy({ it.effectiveFrom }, { it.version }))

        if (matrix == null) {
            val check = CheckResult(
                requirement = REQ_MATRIX,
                status = CheckStatus.FAIL,
                detail = "no competency matrix effective on or before $asOf for course $courseId",
                missing = "a published competency matrix version effective by $asOf",
            )
            return false to EvaluationOutcome(null, listOf(check))
        }

        val checks = ArrayList<CheckResult>()
        checks.add(
            CheckResult(
                requirement = REQ_MATRIX,
                status = CheckStatus.PASS,
                detail = "using course $courseId matrix v${matrix.version} effective ${matrix.effectiveFrom}",
                evidenceEventIds = listOf(matrix.eventId),
            ),
        )

        checks.add(evaluatePractice(matrix, state))
        matrix.requiredCertType?.let { checks.add(evaluateCertification(it, asOf, state)) }
        matrix.requiredProjectDomain?.let { checks.add(evaluateProject(it, asOf, state)) }

        val qualified = checks.all { it.status == CheckStatus.PASS }
        return qualified to EvaluationOutcome(matrix.version, checks)
    }

    private fun evaluatePractice(matrix: MatrixVersionFact, state: ProjectedState): CheckResult {
        val merged = IntervalMerger.merge(state.practice.map { it.interval })
        val totalDays = merged.sumOf { it.days() }
        // Evidence: every practice record contributing a covered day (i.e. all of them, since
        // merge only unions existing intervals).
        val evidenceIds = state.practice.map { it.eventId }
        return if (totalDays >= matrix.minPracticeDays) {
            CheckResult(
                requirement = REQ_PRACTICE,
                status = CheckStatus.PASS,
                detail = "$totalDays distinct practice days (overlaps merged) >= required ${matrix.minPracticeDays}",
                evidenceEventIds = evidenceIds,
            )
        } else {
            CheckResult(
                requirement = REQ_PRACTICE,
                status = CheckStatus.FAIL,
                detail = "$totalDays distinct practice days (overlaps merged) < required ${matrix.minPracticeDays}",
                evidenceEventIds = evidenceIds,
                missing = "${matrix.minPracticeDays - totalDays} more distinct enterprise-practice days",
            )
        }
    }

    private fun evaluateCertification(requiredType: String, asOf: LocalDate, state: ProjectedState): CheckResult {
        val valid = state.certifications.firstOrNull { it.certType == requiredType && it.isValidOn(asOf) }
        if (valid != null) {
            val evidence = listOfNotNull(valid.issueEventId)
            return CheckResult(
                requirement = REQ_CERTIFICATION,
                status = CheckStatus.PASS,
                detail = "certification ${valid.certId} ($requiredType) valid on $asOf",
                evidenceEventIds = evidence,
            )
        }
        // Explain why the best-matching cert (if any) failed, citing the blocking event.
        val candidate = state.certifications.firstOrNull { it.certType == requiredType }
        val (detail, evidence) = when {
            candidate == null ->
                "no certification of type $requiredType on record" to emptyList()
            asOf.isBefore(candidate.issuedOn) ->
                "certification ${candidate.certId} not yet issued on $asOf (issued ${candidate.issuedOn})" to
                    listOf(candidate.issueEventId)
            asOf.isAfter(candidate.expiresOn) ->
                "certification ${candidate.certId} expired on $asOf (expired ${candidate.expiresOn})" to
                    listOf(candidate.issueEventId)
            candidate.revokedEffectiveOn != null && !asOf.isBefore(candidate.revokedEffectiveOn) ->
                "certification ${candidate.certId} revoked effective ${candidate.revokedEffectiveOn}" to
                    listOfNotNull(candidate.issueEventId, candidate.revokeEventId)
            else -> "certification ${candidate.certId} not valid on $asOf" to listOf(candidate.issueEventId)
        }
        return CheckResult(
            requirement = REQ_CERTIFICATION,
            status = CheckStatus.FAIL,
            detail = detail,
            evidenceEventIds = evidence,
            missing = "a valid $requiredType certification on $asOf",
        )
    }

    private fun evaluateProject(requiredDomain: String, asOf: LocalDate, state: ProjectedState): CheckResult {
        val accepted = state.projectRoles.firstOrNull {
            it.domain == requiredDomain && it.accepted && it.acceptedOn != null && !it.acceptedOn.isAfter(asOf)
        }
        if (accepted != null) {
            return CheckResult(
                requirement = REQ_PROJECT,
                status = CheckStatus.PASS,
                detail = "accepted project ${accepted.projectId} in $requiredDomain (accepted ${accepted.acceptedOn})",
                evidenceEventIds = listOf(accepted.eventId),
            )
        }
        val candidate = state.projectRoles.firstOrNull { it.domain == requiredDomain }
        val detail = when {
            candidate == null -> "no industry project in $requiredDomain on record"
            !candidate.accepted -> "project ${candidate.projectId} in $requiredDomain not yet enterprise-accepted"
            candidate.acceptedOn != null && candidate.acceptedOn.isAfter(asOf) ->
                "project ${candidate.projectId} accepted ${candidate.acceptedOn}, after $asOf"
            else -> "project ${candidate.projectId} in $requiredDomain not usable as evidence on $asOf"
        }
        return CheckResult(
            requirement = REQ_PROJECT,
            status = CheckStatus.FAIL,
            detail = detail,
            evidenceEventIds = listOfNotNull(candidate?.eventId),
            missing = "an enterprise-accepted industry project in $requiredDomain by $asOf",
        )
    }
}

/** Evaluator output: the matrix version applied (if any) plus the ordered checks. */
data class EvaluationOutcome(val matrixVersion: Int?, val checks: List<CheckResult>)
