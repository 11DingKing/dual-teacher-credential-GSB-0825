package com.dualteacher.api

import com.dualteacher.domain.LocalDateSerializer
import com.dualteacher.domain.SnapshotItem
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class EventRequest(
    val eventId: String,
    val eventType: String,
    val aggregateId: String,
    @Serializable(with = LocalDateSerializer::class)
    val occurredAt: LocalDate,
    val payload: kotlinx.serialization.json.JsonObject
)

@Serializable
data class EventResponse(
    val eventId: String,
    val accepted: Boolean,
    val message: String
)

@Serializable
data class QualificationResponse(
    val decisionId: String,
    val teacherId: String,
    val courseId: String,
    @Serializable(with = LocalDateSerializer::class)
    val asOf: LocalDate,
    val overallResult: String,
    val items: List<SnapshotItem>
)

@Serializable
data class DecisionResponse(
    val decisionId: String,
    val teacherId: String,
    val courseId: String,
    @Serializable(with = LocalDateSerializer::class)
    val asOf: LocalDate,
    val overallResult: String,
    val items: List<SnapshotItem>
)

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String
)
