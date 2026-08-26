package com.dualteacher.service

import com.dualteacher.domain.*
import com.dualteacher.persistence.StoredEvent
import kotlinx.serialization.json.Json

class EventProjector {

    private val json = Json { ignoreUnknownKeys = true }

    fun projectTeacherEvents(
        teacherId: String,
        events: List<StoredEvent>
    ): ProjectedState {
        val sorted = events.sortedWith(
            compareBy<StoredEvent> { it.occurredAt }
                .thenBy { it.eventId }
        )

        var teacher: Teacher? = null
        val practiceIntervals = mutableListOf<PracticeInterval>()
        val certifications = mutableMapOf<String, Certification>()
        val projects = mutableMapOf<String, IndustryProject>()
        val matrices = mutableListOf<CapabilityMatrix>()

        for (event in sorted) {
            when (event.eventType) {
                EventType.TEACHER_CREATED -> {
                    val payload = json.decodeFromJsonElement(
                        TeacherCreatedPayload.serializer(),
                        event.payload
                    )
                    teacher = Teacher(teacherId = event.aggregateId, name = payload.name)
                }

                EventType.PRACTICE_RECORDED -> {
                    val payload = json.decodeFromJsonElement(
                        PracticeRecordedPayload.serializer(),
                        event.payload
                    )
                    practiceIntervals.add(
                        PracticeInterval(
                            intervalId = payload.intervalId,
                            teacherId = event.aggregateId,
                            startDate = payload.startDate,
                            endDate = payload.endDate,
                            company = payload.company,
                            role = payload.role,
                            eventId = event.eventId
                        )
                    )
                }

                EventType.CERTIFICATION_ISSUED -> {
                    val payload = json.decodeFromJsonElement(
                        CertificationIssuedPayload.serializer(),
                        event.payload
                    )
                    certifications[payload.certificationId] = Certification(
                        certificationId = payload.certificationId,
                        teacherId = event.aggregateId,
                        certType = payload.certType,
                        issuedDate = event.occurredAt,
                        expiryDate = payload.expiryDate,
                        issuer = payload.issuer,
                        issueEventId = event.eventId
                    )
                }

                EventType.CERTIFICATION_REVOKED -> {
                    val payload = json.decodeFromJsonElement(
                        CertificationRevokedPayload.serializer(),
                        event.payload
                    )
                    val existing = certifications[payload.certificationId]
                    if (existing != null && existing.revokedAt == null) {
                        certifications[payload.certificationId] = existing.copy(
                            revokedAt = event.occurredAt,
                            revokeEventId = event.eventId
                        )
                    }
                }

                EventType.PROJECT_RECORDED -> {
                    val payload = json.decodeFromJsonElement(
                        ProjectRecordedPayload.serializer(),
                        event.payload
                    )
                    projects[payload.projectId] = IndustryProject(
                        projectId = payload.projectId,
                        teacherId = event.aggregateId,
                        projectName = payload.projectName,
                        role = payload.role,
                        startDate = payload.startDate,
                        endDate = payload.endDate,
                        company = payload.company,
                        recordEventId = event.eventId
                    )
                }

                EventType.PROJECT_ACCEPTED -> {
                    val payload = json.decodeFromJsonElement(
                        ProjectAcceptedPayload.serializer(),
                        event.payload
                    )
                    val existing = projects[payload.projectId]
                    if (existing != null && existing.acceptedAt == null) {
                        projects[payload.projectId] = existing.copy(
                            acceptedAt = event.occurredAt,
                            acceptEventId = event.eventId
                        )
                    }
                }

                EventType.MATRIX_PUBLISHED -> {
                    val payload = json.decodeFromJsonElement(
                        MatrixPublishedPayload.serializer(),
                        event.payload
                    )
                    matrices.add(
                        CapabilityMatrix(
                            matrixId = payload.matrixId,
                            courseId = payload.courseId,
                            version = payload.version,
                            effectiveDate = event.occurredAt,
                            requirements = payload.requirements,
                            eventId = event.eventId
                        )
                    )
                }
            }
        }

        return ProjectedState(
            teacher = teacher,
            practiceIntervals = practiceIntervals,
            certifications = certifications.values.toList(),
            projects = projects.values.toList(),
            matrices = matrices
        )
    }
}
