package com.gsb.dualteacher.web

import com.gsb.dualteacher.domain.EventEnvelope
import com.gsb.dualteacher.service.EventService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.MDC

@Serializable
data class IngestResponse(val eventId: String, val duplicate: Boolean)

fun Route.eventRoutes(eventService: EventService) {
    route("/api/v1/events") {
        post {
            val envelope = call.receive<EventEnvelope>()
            MDC.put("eventId", envelope.eventId)
            val result = eventService.ingest(envelope)
            val status = if (result.duplicate) HttpStatusCode.OK else HttpStatusCode.Created
            call.respond(status, IngestResponse(result.eventId, result.duplicate))
        }
    }
}
