package com.gsb.dualteacher

import com.gsb.dualteacher.config.DatabaseFactory
import com.gsb.dualteacher.config.resolveDataSourceConfig
import com.gsb.dualteacher.db.DecisionRepository
import com.gsb.dualteacher.db.EventRepository
import com.gsb.dualteacher.db.HealthRepository
import com.gsb.dualteacher.service.EventService
import com.gsb.dualteacher.service.QualificationService
import com.gsb.dualteacher.service.TeacherNotFoundException
import com.gsb.dualteacher.service.EventValidationException
import com.gsb.dualteacher.web.DecisionNotFoundException
import com.gsb.dualteacher.web.decisionRoutes
import com.gsb.dualteacher.web.eventRoutes
import com.gsb.dualteacher.web.healthRoutes
import com.gsb.dualteacher.web.openApiRoute
import com.gsb.dualteacher.web.qualificationRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

@Serializable
data class ErrorResponse(val error: String, val message: String)

class AppServices(
    val eventService: EventService,
    val qualificationService: QualificationService,
    val healthRepository: HealthRepository,
)

fun defaultServices(): AppServices = AppServices(
    eventService = EventService(EventRepository()),
    qualificationService = QualificationService(EventRepository(), DecisionRepository()),
    healthRepository = HealthRepository(),
)

fun Application.appModule(services: AppServices) {
    install(DefaultHeaders)
    install(CallLogging) {
        mdc("requestId") { UUID.randomUUID().toString() }
    }
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            },
        )
    }
    install(StatusPages) {
        exception<EventValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_event", cause.message ?: "invalid event"))
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad_request", cause.message ?: "bad request"))
        }
        exception<TeacherNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("teacher_not_found", cause.message ?: "teacher not found"))
        }
        exception<DecisionNotFoundException> { call, cause ->
            call.respond(HttpStatusCode.NotFound, ErrorResponse("decision_not_found", cause.message ?: "decision not found"))
        }
    }

    routing {
        eventRoutes(services.eventService)
        qualificationRoutes(services.qualificationService)
        decisionRoutes(services.qualificationService)
        healthRoutes(services.healthRepository)
        openApiRoute()
    }
}

private val log = LoggerFactory.getLogger("Application")

fun main() {
    val config = resolveDataSourceConfig()
    DatabaseFactory.connect(config)

    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    log.info("starting dual-teacher-credential service on port {}", port)
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        appModule(defaultServices())
    }.start(wait = true)
}
