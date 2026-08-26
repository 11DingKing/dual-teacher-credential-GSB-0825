package com.dualteacher.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * Source facts recorded by upstream departments. Each concrete payload carries the
 * business data of one fact; the surrounding [EventEnvelope] carries identity and
 * business time. Payloads are polymorphic on the JSON `type` discriminator.
 */
@Serializable
sealed class EventPayload {
    /** Registers a teacher so later facts can reference them. */
    @Serializable
    @SerialName("TeacherRegistered")
    data class TeacherRegistered(
        @SerialName("teacher_id") val teacherId: String,
        val name: String,
    ) : EventPayload()

    /** A closed enterprise-practice interval [startDate, endDate] (inclusive). */
    @Serializable
    @SerialName("PracticeRecorded")
    data class PracticeRecorded(
        @SerialName("teacher_id") val teacherId: String,
        val enterprise: String,
        @Serializable(LocalDateSerializer::class) @SerialName("start_date") val startDate: LocalDate,
        @Serializable(LocalDateSerializer::class) @SerialName("end_date") val endDate: LocalDate,
    ) : EventPayload()

    /** Issuance of an equipment/skill certification, valid through [expiresOn] inclusive. */
    @Serializable
    @SerialName("CertificationIssued")
    data class CertificationIssued(
        @SerialName("teacher_id") val teacherId: String,
        @SerialName("cert_id") val certId: String,
        @SerialName("cert_type") val certType: String,
        @Serializable(LocalDateSerializer::class) @SerialName("issued_on") val issuedOn: LocalDate,
        @Serializable(LocalDateSerializer::class) @SerialName("expires_on") val expiresOn: LocalDate,
    ) : EventPayload()

    /**
     * Revocation of a previously issued certification. [effectiveOn] is the business date
     * from which the certification is no longer valid — independent of when this event
     * was received.
     */
    @Serializable
    @SerialName("CertificationRevoked")
    data class CertificationRevoked(
        @SerialName("teacher_id") val teacherId: String,
        @SerialName("cert_id") val certId: String,
        @Serializable(LocalDateSerializer::class) @SerialName("effective_on") val effectiveOn: LocalDate,
    ) : EventPayload()

    /**
     * A role held on an industry project. Only counts as evidence once the enterprise has
     * accepted the deliverable ([accepted] = true with an [acceptedOn] date).
     */
    @Serializable
    @SerialName("IndustryProjectRoleRecorded")
    data class IndustryProjectRoleRecorded(
        @SerialName("teacher_id") val teacherId: String,
        @SerialName("project_id") val projectId: String,
        val role: String,
        val domain: String,
        val accepted: Boolean = false,
        @Serializable(LocalDateSerializer::class) @SerialName("accepted_on") val acceptedOn: LocalDate? = null,
    ) : EventPayload()

    /**
     * Publishes a versioned competency matrix for a course. The version effective at the
     * query's `as_of` (latest [effectiveFrom] <= as_of) drives the requirements.
     */
    @Serializable
    @SerialName("CompetencyMatrixPublished")
    data class CompetencyMatrixPublished(
        @SerialName("course_id") val courseId: String,
        val version: Int,
        @Serializable(LocalDateSerializer::class) @SerialName("effective_from") val effectiveFrom: LocalDate,
        @SerialName("min_practice_days") val minPracticeDays: Int,
        @SerialName("required_cert_type") val requiredCertType: String? = null,
        @SerialName("required_project_domain") val requiredProjectDomain: String? = null,
    ) : EventPayload()
}

/**
 * Identity + business time wrapper around a payload.
 *
 * @property eventId globally unique idempotency key; duplicate ids are ignored on ingest.
 * @property occurredAt business time the fact happened; projection orders by this, not by
 *   the server receipt order.
 */
@Serializable
data class EventEnvelope(
    @SerialName("event_id") val eventId: String,
    @Serializable(InstantSerializer::class) @SerialName("occurred_at") val occurredAt: java.time.Instant,
    val payload: EventPayload,
)
