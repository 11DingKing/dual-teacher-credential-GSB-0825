package com.dualteacher.domain

import kotlinx.serialization.Serializable
import java.time.LocalDate

data class Teacher(
    val teacherId: String,
    val name: String
)

data class PracticeInterval(
    val intervalId: String,
    val teacherId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val company: String,
    val role: String?,
    val eventId: String
)

data class Certification(
    val certificationId: String,
    val teacherId: String,
    val certType: String,
    val issuedDate: LocalDate,
    val expiryDate: LocalDate,
    val issuer: String,
    val revokedAt: LocalDate? = null,
    val issueEventId: String,
    val revokeEventId: String? = null
) {
    fun isValidOn(date: LocalDate): Boolean {
        if (date.isBefore(issuedDate)) return false
        if (date.isAfter(expiryDate)) return false
        if (revokedAt != null && !date.isBefore(revokedAt)) return false
        return true
    }
}

data class IndustryProject(
    val projectId: String,
    val teacherId: String,
    val projectName: String,
    val role: String,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val company: String,
    val acceptedAt: LocalDate? = null,
    val recordEventId: String,
    val acceptEventId: String? = null
) {
    fun isAcceptedBy(date: LocalDate): Boolean {
        return acceptedAt != null && !acceptedAt.isAfter(date)
    }
}

data class CapabilityMatrix(
    val matrixId: String,
    val courseId: String,
    val version: Int,
    val effectiveDate: LocalDate,
    val requirements: MatrixRequirements,
    val eventId: String
)

data class ProjectedState(
    val teacher: Teacher?,
    val practiceIntervals: List<PracticeInterval>,
    val certifications: List<Certification>,
    val projects: List<IndustryProject>,
    val matrices: List<CapabilityMatrix>
) {
    companion object {
        val EMPTY = ProjectedState(null, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

enum class QualificationStatus { PASS, FAIL }

data class RequirementItem(
    val key: String,
    val label: String,
    val status: QualificationStatus,
    val required: String,
    val actual: String,
    val evidenceIds: List<String>,
    val missing: String?
)

data class QualificationResult(
    val teacherId: String,
    val courseId: String,
    val asOf: LocalDate,
    val overallResult: QualificationStatus,
    val items: List<RequirementItem>
) {
    fun toSnapshot(): DecisionSnapshot = DecisionSnapshot(
        teacherId = teacherId,
        courseId = courseId,
        asOf = asOf,
        overallResult = overallResult.name,
        items = items.map {
            SnapshotItem(
                key = it.key,
                label = it.label,
                status = it.status.name,
                required = it.required,
                actual = it.actual,
                evidenceIds = it.evidenceIds,
                missing = it.missing
            )
        }
    )
}

@Serializable
data class SnapshotItem(
    val key: String,
    val label: String,
    val status: String,
    val required: String,
    val actual: String,
    val evidenceIds: List<String>,
    val missing: String? = null
)

@Serializable
data class DecisionSnapshot(
    val teacherId: String,
    val courseId: String,
    @Serializable(with = LocalDateSerializer::class)
    val asOf: LocalDate,
    val overallResult: String,
    val items: List<SnapshotItem>
)
