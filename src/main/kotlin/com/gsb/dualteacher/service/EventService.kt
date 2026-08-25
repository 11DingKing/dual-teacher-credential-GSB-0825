package com.gsb.dualteacher.service

import com.gsb.dualteacher.db.EventRepository
import com.gsb.dualteacher.db.NewEvent
import com.gsb.dualteacher.db.uuidOf
import com.gsb.dualteacher.domain.CertIssuedPayload
import com.gsb.dualteacher.domain.CertRevokedPayload
import com.gsb.dualteacher.domain.EventEnvelope
import com.gsb.dualteacher.domain.EventTypes
import com.gsb.dualteacher.domain.MatrixPayload
import com.gsb.dualteacher.domain.PracticePayload
import com.gsb.dualteacher.domain.ProjectAcceptedPayload
import com.gsb.dualteacher.domain.ProjectAssignedPayload
import com.gsb.dualteacher.domain.TeacherRegisteredPayload
import com.gsb.dualteacher.domain.QualificationEvaluator
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class EventValidationException(message: String) : IllegalArgumentException(message)

class EventService(private val repository: EventRepository) {

    data class IngestResult(val eventId: String, val duplicate: Boolean)

    /**
     * 接入来源事件。
     *  - eventId 必须是全局唯一 UUID，重复投递幂等返回 duplicate=true；
     *  - occurredAt 是业务发生时间（支持 yyyy-MM-dd 或 ISO-8601 时刻），乱序到达不影响投影；
     *  - payload 按事件类型校验，teacherId 以 payload 为准并冗余到事件行上便于检索。
     */
    suspend fun ingest(envelope: EventEnvelope): IngestResult {
        if (envelope.eventType !in EventTypes.ALL) {
            throw EventValidationException("unknown eventType: ${envelope.eventType}")
        }
        val eventId = uuidOf(envelope.eventId)
        val occurredAt = parseBusinessTime(envelope.occurredAt)

        val payload = envelope.payload
        val teacherId = when (envelope.eventType) {
            EventTypes.TEACHER_REGISTERED -> decode<TeacherRegisteredPayload>(eventId, payload).teacherId
            EventTypes.PRACTICE_RECORDED -> decode<PracticePayload>(eventId, payload).run {
                requireDateRange(eventId, startDate, endDate)
                teacherId
            }
            EventTypes.CERT_ISSUED -> decode<CertIssuedPayload>(eventId, payload).run {
                val issued = LocalDate.parse(issuedOn)
                val expires = LocalDate.parse(expiresOn)
                if (expires.isBefore(issued)) {
                    throw EventValidationException("event $eventId: expiresOn $expiresOn 早于 issuedOn $issuedOn")
                }
                teacherId
            }
            EventTypes.CERT_REVOKED -> decode<CertRevokedPayload>(eventId, payload).teacherId
            EventTypes.PROJECT_ROLE_ASSIGNED -> decode<ProjectAssignedPayload>(eventId, payload).teacherId
            EventTypes.PROJECT_ACCEPTED -> decode<ProjectAcceptedPayload>(eventId, payload).teacherId
            EventTypes.MATRIX_PUBLISHED -> {
                decode<MatrixPayload>(eventId, payload)
                null
            }
            else -> envelope.teacherId
        }

        val payloadJson = QualificationEvaluator.json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            payload,
        )
        val duplicate = !repository.insertIfAbsent(
            NewEvent(
                eventId = eventId,
                eventType = envelope.eventType,
                teacherId = teacherId,
                occurredAt = occurredAt,
                payloadJson = payloadJson,
            ),
        )
        return IngestResult(envelope.eventId, duplicate)
    }

    private inline fun <reified T> decode(eventId: java.util.UUID, payload: kotlinx.serialization.json.JsonObject): T =
        try {
            QualificationEvaluator.json.decodeFromJsonElement<T>(payload)
        } catch (e: Exception) {
            throw EventValidationException("event $eventId: payload 不符合 ${T::class.simpleName}: ${e.message}")
        }

    private fun requireDateRange(eventId: java.util.UUID, startDate: String, endDate: String) {
        val start = LocalDate.parse(startDate)
        val end = LocalDate.parse(endDate)
        if (end.isBefore(start)) {
            throw EventValidationException("event $eventId: endDate $endDate 早于 startDate $startDate")
        }
    }

    private fun parseBusinessTime(value: String): Instant {
        val trimmed = value.trim()
        return try {
            if (trimmed.length == 10) {
                LocalDate.parse(trimmed).atStartOfDay(ZoneOffset.UTC).toInstant()
            } else {
                Instant.parse(trimmed)
            }
        } catch (e: Exception) {
            throw EventValidationException("occurredAt 必须是 yyyy-MM-dd 或 ISO-8601 时刻: $value")
        }
    }
}
