package com.dualteacher.api

import com.dualteacher.domain.EventEnvelope
import com.dualteacher.service.QualificationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.LocalDate
import javax.sql.DataSource

/** Registers all HTTP routes for the qualification service. */
fun Route.qualificationRoutes(service: QualificationService, dataSource: DataSource) {

    // Liveness/readiness: verifies a database round-trip.
    get("/health") {
        val dbOk = runCatching {
            dataSource.connection.use { it.isValid(2) }
        }.getOrDefault(false)
        val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
        call.respond(status, mapOf("status" to if (dbOk) "UP" else "DOWN", "database" to if (dbOk) "UP" else "DOWN"))
    }

    // Ingest a source event. Idempotent on event_id.
    post("/events") {
        val env = call.receive<EventEnvelope>()
        val stored = service.append(env)
        val msg = if (stored) "event stored" else "duplicate event_id ignored (idempotent)"
        call.respond(HttpStatusCode.OK, AppendEventResponse(env.eventId, stored, msg))
    }

    // Evaluate a teacher's qualification for a course as of a date.
    get("/teachers/{teacherId}/qualification") {
        val teacherId = call.parameters["teacherId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing teacherId"))
        val courseId = call.request.queryParameters["course_id"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing course_id query parameter"))
        val asOfRaw = call.request.queryParameters["as_of"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing as_of query parameter"))
        val asOf = runCatching { LocalDate.parse(asOfRaw) }.getOrNull()
            ?: return@get call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid as_of", "expected ISO date (YYYY-MM-DD), got '$asOfRaw'"),
            )

        val decision = service.evaluate(teacherId, courseId, asOf)
        call.respond(HttpStatusCode.OK, decision)
    }

    // Retrieve a previously recorded immutable decision snapshot.
    get("/decisions/{decisionId}") {
        val decisionId = call.parameters["decisionId"]
            ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing decisionId"))
        val decision = service.getDecision(decisionId)
            ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("decision not found", decisionId))
        call.respond(HttpStatusCode.OK, decision)
    }
}
