package com.dualteacher.api

import com.dualteacher.domain.EventEnvelope
import com.dualteacher.domain.EventType
import com.dualteacher.persistence.EventStore
import com.dualteacher.persistence.StoredDecision
import com.dualteacher.service.QualificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun Route.eventRoutes(eventStore: EventStore) {
    route("/api/v1/events") {
        post {
            val request = call.receive<EventRequest>()

            val eventType = try {
                EventType.valueOf(request.eventType.uppercase())
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_event_type", "Unknown event type: ${request.eventType}")
                )
                return@post
            }

            val envelope = EventEnvelope(
                eventId = request.eventId,
                eventType = eventType,
                aggregateId = request.aggregateId,
                occurredAt = request.occurredAt,
                payload = request.payload
            )

            val accepted = eventStore.append(envelope)

            logger.info {
                "Event ${envelope.eventId} type=${envelope.eventType} aggregate=${envelope.aggregateId} " +
                    "occurred_at=${envelope.occurredAt} accepted=$accepted"
            }

            val status = if (accepted) HttpStatusCode.Created else HttpStatusCode.OK
            call.respond(
                status,
                EventResponse(
                    eventId = envelope.eventId,
                    accepted = accepted,
                    message = if (accepted) "Event accepted" else "Event already exists (idempotent)"
                )
            )
        }
    }
}

fun Route.qualificationRoutes(qualificationService: QualificationService) {
    route("/api/v1/teachers/{teacherId}/qualification") {
        get {
            val teacherId = call.parameters["teacherId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "teacherId is required")
                )

            val courseId = call.request.queryParameters["course_id"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "course_id query parameter is required")
                )

            val asOfStr = call.request.queryParameters["as_of"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "as_of query parameter is required (YYYY-MM-DD)")
                )

            val asOf = try {
                LocalDate.parse(asOfStr)
            } catch (e: Exception) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_date", "as_of must be a valid date in YYYY-MM-DD format")
                )
            }

            val decision = qualificationService.queryQualification(teacherId, courseId, asOf)
            call.respond(decision.toQualificationResponse())
        }
    }

    route("/api/v1/decisions/{decisionId}") {
        get {
            val decisionIdStr = call.parameters["decisionId"]
                ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("missing_parameter", "decisionId is required")
                )

            val decisionId = try {
                UUID.fromString(decisionIdStr)
            } catch (e: IllegalArgumentException) {
                return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("invalid_uuid", "decisionId must be a valid UUID")
                )
            }

            val decision = qualificationService.getDecision(decisionId)
                ?: return@get call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("not_found", "Decision $decisionId not found")
                )

            call.respond(decision.toDecisionResponse())
        }
    }
}

fun Route.healthRoutes() {
    get("/health") {
        call.respond(
            mapOf(
                "status" to "UP",
                "service" to "dual-teacher-credential",
                "timestamp" to LocalDate.now().toString()
            )
        )
    }
}

private fun StoredDecision.toQualificationResponse(): QualificationResponse {
    return QualificationResponse(
        decisionId = decisionId.toString(),
        teacherId = teacherId,
        courseId = courseId,
        asOf = asOf,
        overallResult = overallResult.name,
        items = snapshot.items
    )
}

private fun StoredDecision.toDecisionResponse(): DecisionResponse {
    return DecisionResponse(
        decisionId = decisionId.toString(),
        teacherId = teacherId,
        courseId = courseId,
        asOf = asOf,
        overallResult = overallResult.name,
        items = snapshot.items
    )
}
