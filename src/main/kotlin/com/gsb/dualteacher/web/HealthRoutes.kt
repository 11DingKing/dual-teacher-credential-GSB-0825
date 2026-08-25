package com.gsb.dualteacher.web

import com.gsb.dualteacher.db.HealthRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val database: String? = null)

fun Route.healthRoutes(healthRepository: HealthRepository) {
    get("/health") {
        call.respond(HealthResponse(status = "UP"))
    }
    get("/health/ready") {
        val dbOk = try {
            healthRepository.ping()
        } catch (e: Exception) {
            false
        }
        if (dbOk) {
            call.respond(HealthResponse(status = "UP", database = "UP"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "DOWN", database = "DOWN"))
        }
    }
}
