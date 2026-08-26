package com.dualteacher

import com.dualteacher.api.ErrorResponse
import com.dualteacher.api.eventRoutes
import com.dualteacher.api.healthRoutes
import com.dualteacher.api.qualificationRoutes
import com.dualteacher.config.DatabaseConfig
import com.dualteacher.config.DatabaseFactory
import com.dualteacher.persistence.EventStore
import com.dualteacher.service.QualificationService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.Koin
import java.util.UUID

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    val dbUrl = environment.config.propertyOrNull("dualteacher.database.url")?.getString()
        ?: "jdbc:postgresql://localhost:5432/dualteacher"
    val dbUser = environment.config.propertyOrNull("dualteacher.database.user")?.getString()
        ?: "dualteacher"
    val dbPassword = environment.config.propertyOrNull("dualteacher.database.password")?.getString()
        ?: "dualteacher"
    val dbPoolSize = environment.config.propertyOrNull("dualteacher.database.maximumPoolSize")?.getString()
        ?.toIntOrNull() ?: 10

    DatabaseFactory.init(
        DatabaseConfig(
            url = dbUrl,
            user = dbUser,
            password = dbPassword,
            maximumPoolSize = dbPoolSize
        )
    )

    install(Koin) {
        modules(appModule)
    }

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    install(CallId) {
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        callIdMdc("call_id")
        filter { call ->
            call.request.path().startsWith("/api/")
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            logger.error(cause) { "Unhandled exception: ${cause.message}" }
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse("internal_error", cause.message ?: "Internal server error")
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(
                status,
                ErrorResponse("not_found", "The requested resource was not found")
            )
        }
    }

    val eventStore by inject<EventStore>()
    val qualificationService by inject<QualificationService>()

    routing {
        healthRoutes()
        eventRoutes(eventStore)
        qualificationRoutes(qualificationService)
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
    }

    logger.info { "Dual-Teacher Credential Service started successfully" }
}
