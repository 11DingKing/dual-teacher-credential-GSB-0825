package com.gsb.dualteacher.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.openApiRoute() {
    get("/openapi.json") {
        val spec = Thread.currentThread().contextClassLoader
            .getResource("openapi/openapi.json")
            ?.readText()
        if (spec != null) {
            call.respondText(spec, ContentType.Application.Json)
        } else {
            call.respondText(
                """{"error":"openapi spec not found"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
        }
    }
}
