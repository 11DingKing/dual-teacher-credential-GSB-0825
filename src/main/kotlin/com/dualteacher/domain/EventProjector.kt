package com.dualteacher.domain

import java.time.LocalDate

/** A practice interval together with the event id that recorded it (for evidence tracing). */
data class PracticeFact(val interval: DateInterval, val eventId: String)

/** A certification with its issuance window and the ids of the issue + any revocation. */
data class CertificationFact(
    val certId: String,
    val certType: String,
    val issuedOn: LocalDate,
    val expiresOn: LocalDate,
    val issueEventId: String,
    val revokedEffectiveOn: LocalDate? = null,
    val revokeEventId: String? = null,
) {
    /** Valid on [asOf] when within the issuance window and not covered by a revocation whose
     *  effective date has already arrived by [asOf]. */
    fun isValidOn(asOf: LocalDate): Boolean {
        if (asOf.isBefore(issuedOn) || asOf.isAfter(expiresOn)) return false
        val rev = revokedEffectiveOn ?: return true
        return asOf.isBefore(rev)
    }
}

/** An industry-project role, only usable as evidence once enterprise-accepted. */
data class ProjectRoleFact(
    val projectId: String,
    val role: String,
    val domain: String,
    val accepted: Boolean,
    val acceptedOn: LocalDate?,
    val eventId: String,
)

/** A published competency-matrix version for a course. */
data class MatrixVersionFact(
    val courseId: String,
    val version: Int,
    val effectiveFrom: LocalDate,
    val minPracticeDays: Int,
    val requiredCertType: String?,
    val requiredProjectDomain: String?,
    val eventId: String,
)

/**
 * Full projected state derived from the ordered event stream. Later facts for the same
 * key (e.g. a revocation for a cert) update the existing entry rather than appending.
 */
data class ProjectedState(
    val teacherName: String?,
    val practice: List<PracticeFact>,
    val certifications: List<CertificationFact>,
    val projectRoles: List<ProjectRoleFact>,
    val matrixVersions: List<MatrixVersionFact>,
)

/**
 * Deterministically folds events into per-teacher / per-course state.
 *
 * Events are consumed in business-time order ([EventEnvelope.occurredAt], then a stable
 * sequence tiebreak applied by the store) so out-of-order receipt never changes the result.
 */
object EventProjector {

    fun project(teacherId: String, courseId: String, orderedEvents: List<EventEnvelope>): ProjectedState {
        var teacherName: String? = null
        val practice = ArrayList<PracticeFact>()
        val certs = LinkedHashMap<String, CertificationFact>()
        val roles = ArrayList<ProjectRoleFact>()
        val matrices = LinkedHashMap<Int, MatrixVersionFact>()

        for (env in orderedEvents) {
            when (val p = env.payload) {
                is EventPayload.TeacherRegistered ->
                    if (p.teacherId == teacherId) teacherName = p.name

                is EventPayload.PracticeRecorded ->
                    if (p.teacherId == teacherId)
                        practice.add(PracticeFact(DateInterval(p.startDate, p.endDate), env.eventId))

                is EventPayload.CertificationIssued ->
                    if (p.teacherId == teacherId)
                        certs[p.certId] = CertificationFact(
                            certId = p.certId,
                            certType = p.certType,
                            issuedOn = p.issuedOn,
                            expiresOn = p.expiresOn,
                            issueEventId = env.eventId,
                        )

                is EventPayload.CertificationRevoked ->
                    if (p.teacherId == teacherId) {
                        certs[p.certId]?.let { existing ->
                            certs[p.certId] = existing.copy(
                                revokedEffectiveOn = p.effectiveOn,
                                revokeEventId = env.eventId,
                            )
                        }
                    }

                is EventPayload.IndustryProjectRoleRecorded ->
                    if (p.teacherId == teacherId)
                        roles.add(
                            ProjectRoleFact(
                                projectId = p.projectId,
                                role = p.role,
                                domain = p.domain,
                                accepted = p.accepted,
                                acceptedOn = p.acceptedOn,
                                eventId = env.eventId,
                            ),
                        )

                is EventPayload.CompetencyMatrixPublished ->
                    if (p.courseId == courseId)
                        matrices[p.version] = MatrixVersionFact(
                            courseId = p.courseId,
                            version = p.version,
                            effectiveFrom = p.effectiveFrom,
                            minPracticeDays = p.minPracticeDays,
                            requiredCertType = p.requiredCertType,
                            requiredProjectDomain = p.requiredProjectDomain,
                            eventId = env.eventId,
                        )
            }
        }

        return ProjectedState(
            teacherName = teacherName,
            practice = practice,
            certifications = certs.values.toList(),
            projectRoles = roles,
            matrixVersions = matrices.values.toList(),
        )
    }
}
