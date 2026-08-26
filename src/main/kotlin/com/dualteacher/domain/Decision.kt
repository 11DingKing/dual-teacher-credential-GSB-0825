package com.dualteacher.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** Outcome of a single qualification check. */
@Serializable
enum class CheckStatus { PASS, FAIL }

/**
 * One requirement's verdict, the evidence event ids that supported it, and — when failed —
 * a human-readable explanation of what is still missing.
 */
@Serializable
data class CheckResult(
    val requirement: String,
    val status: CheckStatus,
    val detail: String,
    @SerialName("evidence_event_ids") val evidenceEventIds: List<String> = emptyList(),
    val missing: String? = null,
)

/**
 * Immutable qualification decision for one teacher/course/as_of. Persisted verbatim under
 * [decisionId]; recording later material never mutates an existing decision.
 */
@Serializable
data class QualificationDecision(
    @SerialName("decision_id") val decisionId: String,
    @SerialName("teacher_id") val teacherId: String,
    @SerialName("course_id") val courseId: String,
    @Serializable(LocalDateSerializer::class) @SerialName("as_of") val asOf: LocalDate,
    val qualified: Boolean,
    @SerialName("matrix_version") val matrixVersion: Int?,
    val checks: List<CheckResult>,
    @Serializable(InstantSerializer::class) @SerialName("decided_at") val decidedAt: java.time.Instant,
)
