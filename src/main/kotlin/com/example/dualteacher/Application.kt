package com.example.dualteacher

import com.example.dualteacher.config.AppConfig
import com.example.dualteacher.config.DatabaseFactory
import com.example.dualteacher.routes.apiRoutes
import com.example.dualteacher.routes.configureStatusPages
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.slf4j.event.Level

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun main() {
    val config = AppConfig.fromEnv()
    val database = DatabaseFactory.init(config)
    embeddedServer(CIO, port = config.port) { appModule(database) }.start(wait = true)
}

fun Application.appModule(database: Database) {
    install(ContentNegotiation) { json(AppJson) }
    install(CallLogging) { level = Level.INFO }
    configureStatusPages()

    routing {
        apiRoutes(database)
        swaggerUI("swagger", "openapi/openapi.yaml")
        get("/openapi.yaml") {
            val spec = requireNotNull(environment.classLoader.getResource("openapi/openapi.yaml")) {
                "openapi.yaml not found on classpath"
            }.readText()
            call.respondText(spec, ContentType.parse("application/yaml"))
        }
    }
}
