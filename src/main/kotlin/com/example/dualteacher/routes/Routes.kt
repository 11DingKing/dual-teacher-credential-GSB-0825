package com.example.dualteacher.routes

import com.example.dualteacher.domain.ErrorResponse
import com.example.dualteacher.domain.EventRequest
import com.example.dualteacher.domain.HealthResponse
import com.example.dualteacher.service.IngestionService
import com.example.dualteacher.service.QualificationService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "bad request"))
        }
        exception<ContentTransformationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("malformed request body: ${cause.message}"))
        }
        exception<SerializationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid json: ${cause.message}"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(cause.message ?: "invalid request"))
        }
        exception<NotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse(cause.message ?: "not found"))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal server error"))
        }
    }
}

fun Route.apiRoutes(db: Database) {
    val ingestionService = IngestionService(db)
    val qualificationService = QualificationService(db)

    get("/health") {
        val up = try {
            transaction(db) { exec("SELECT 1") { rs -> rs.next() } == true }
        } catch (e: Exception) {
            false
        }
        if (up) {
            call.respond(HttpStatusCode.OK, HealthResponse("UP"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse("DOWN"))
        }
    }

    post("/events") {
        val request = call.receive<EventRequest>()
        call.respond(HttpStatusCode.OK, ingestionService.ingest(request))
    }

    get("/teachers/{teacherId}/qualifications") {
        val teacherId = call.parameters["teacherId"]!!
        val courseId = call.request.queryParameters["course_id"]
            ?: throw BadRequestException("query parameter course_id is required")
        val asOfRaw = call.request.queryParameters["as_of"]
            ?: throw BadRequestException("query parameter as_of is required (YYYY-MM-DD)")
        val asOf = try {
            LocalDate.parse(asOfRaw)
        } catch (e: DateTimeParseException) {
            throw BadRequestException("as_of must be a valid ISO date (YYYY-MM-DD)")
        }
        call.respond(HttpStatusCode.OK, qualificationService.decide(teacherId, courseId, asOf))
    }

    get("/teachers/{teacherId}/portfolio") {
        val teacherId = call.parameters["teacherId"]!!
        call.respond(HttpStatusCode.OK, qualificationService.getPortfolio(teacherId))
    }

    get("/decisions/{decisionId}") {
        val decisionId = try {
            UUID.fromString(call.parameters["decisionId"])
        } catch (e: IllegalArgumentException) {
            throw BadRequestException("decisionId must be a UUID")
        }
        call.respond(HttpStatusCode.OK, qualificationService.getDecision(decisionId))
    }
}
