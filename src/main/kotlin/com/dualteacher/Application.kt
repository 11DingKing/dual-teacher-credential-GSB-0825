package com.dualteacher

import com.dualteacher.api.ErrorResponse
import com.dualteacher.api.qualificationRoutes
import com.dualteacher.app.AppJson
import com.dualteacher.db.DatabaseFactory
import com.dualteacher.db.DbConfig
import com.dualteacher.repo.DecisionRepository
import com.dualteacher.repo.EventStore
import com.dualteacher.service.QualificationService
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import java.util.UUID

fun main() {
    val databaseUrl = System.getenv("DATABASE_URL")
        ?: error("DATABASE_URL is required, e.g. postgresql://user:pass@localhost:5432/dual_teacher")
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    val factory = DatabaseFactory(DbConfig.fromDatabaseUrl(databaseUrl))
    factory.connect()
    Runtime.getRuntime().addShutdownHook(Thread { factory.close() })

    val service = QualificationService(EventStore(), DecisionRepository())

    embeddedServer(Netty, port = port) {
        module(service, factory)
    }.start(wait = true)
}

/** Wires plugins and routes. Extracted so tests can drive it without a real Netty engine. */
fun Application.module(service: QualificationService, factory: DatabaseFactory) {
    install(ContentNegotiation) { json(AppJson) }

    install(DefaultHeaders)

    install(CallId) {
        header("X-Request-Id")
        generate { UUID.randomUUID().toString() }
        verify { it.isNotBlank() }
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("request_id")
    }

    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("bad request", cause.message))
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("internal error", cause.message))
        }
    }

    routing {
        qualificationRoutes(service, factory.dataSource)
        get("/openapi.yaml") {
            val spec = this::class.java.classLoader.getResource("openapi.yaml")?.readText()
            if (spec == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("openapi spec not found"))
            } else {
                call.respondText(spec, io.ktor.http.ContentType.parse("application/yaml"))
            }
        }
    }
}
