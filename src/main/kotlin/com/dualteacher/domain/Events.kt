package com.dualteacher.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): LocalDate {
        return LocalDate.parse(decoder.decodeString())
    }
}

@Serializable
enum class EventType {
    TEACHER_CREATED,
    PRACTICE_RECORDED,
    CERTIFICATION_ISSUED,
    CERTIFICATION_REVOKED,
    PROJECT_RECORDED,
    PROJECT_ACCEPTED,
    MATRIX_PUBLISHED
}

@Serializable
data class EventEnvelope(
    val eventId: String,
    val eventType: EventType,
    val aggregateId: String,
    @Serializable(with = LocalDateSerializer::class)
    val occurredAt: LocalDate,
    val payload: JsonObject
)

@Serializable
data class TeacherCreatedPayload(
    val name: String
)

@Serializable
data class PracticeRecordedPayload(
    val intervalId: String,
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate,
    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate,
    val company: String,
    val role: String? = null
)

@Serializable
data class CertificationIssuedPayload(
    val certificationId: String,
    val certType: String,
    @Serializable(with = LocalDateSerializer::class)
    val expiryDate: LocalDate,
    val issuer: String
)

@Serializable
data class CertificationRevokedPayload(
    val certificationId: String,
    val reason: String? = null
)

@Serializable
data class ProjectRecordedPayload(
    val projectId: String,
    val projectName: String,
    val role: String,
    @Serializable(with = LocalDateSerializer::class)
    val startDate: LocalDate? = null,
    @Serializable(with = LocalDateSerializer::class)
    val endDate: LocalDate? = null,
    val company: String
)

@Serializable
data class ProjectAcceptedPayload(
    val projectId: String
)

@Serializable
data class MatrixPublishedPayload(
    val matrixId: String,
    val courseId: String,
    val version: Int,
    val requirements: MatrixRequirements
)

@Serializable
data class MatrixRequirements(
    val minPracticeDays: Int,
    val requiredCertTypes: List<String>,
    val minAcceptedProjects: Int
)
