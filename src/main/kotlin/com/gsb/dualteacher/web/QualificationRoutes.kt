package com.gsb.dualteacher.web

import com.gsb.dualteacher.service.QualificationService
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

fun Route.qualificationRoutes(qualificationService: QualificationService) {
    route("/api/v1/teachers/{teacherId}/qualification") {
        get {
            val teacherId = call.parameters["teacherId"]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("teacherId 不能为空")

            val courseCode = call.request.queryParameters["course"]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("查询参数 course 不能为空")

            val asOfRaw = call.request.queryParameters["asOf"]
                ?: call.request.queryParameters["as_of"]
                ?: throw IllegalArgumentException("查询参数 asOf（yyyy-MM-dd）不能为空")
            val asOf = try {
                LocalDate.parse(asOfRaw)
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException("asOf 必须是 yyyy-MM-dd 日期: $asOfRaw")
            }

            val snapshot = qualificationService.evaluate(teacherId, courseCode, asOf)
            call.respond(snapshot)
        }
    }
}

fun Route.decisionRoutes(qualificationService: QualificationService) {
    route("/api/v1/decisions/{decisionId}") {
        get {
            val raw = call.parameters["decisionId"]
                ?: throw IllegalArgumentException("decisionId 不能为空")
            val decisionId = try {
                UUID.fromString(raw)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("decisionId 不是合法 UUID: $raw")
            }
            val snapshot = qualificationService.getDecision(decisionId)
                ?: throw DecisionNotFoundException(raw)
            call.respond(snapshot)
        }
    }
}

class DecisionNotFoundException(decisionId: String) :
    RuntimeException("决定 $decisionId 不存在")
