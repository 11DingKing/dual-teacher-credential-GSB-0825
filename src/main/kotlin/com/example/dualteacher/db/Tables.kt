package com.example.dualteacher.db

import com.example.dualteacher.domain.DecisionItem
import com.example.dualteacher.domain.Requirements
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

private val tablesJson = Json { ignoreUnknownKeys = true }

object Events : Table("events") {
    val eventId = text("event_id")
    val eventType = text("event_type")
    val occurredAt = timestampWithTimeZone("occurred_at").nullable()
    val payload = jsonb<JsonObject>("payload", { it.toString() }, { Json.parseToJsonElement(it).jsonObject })
    val receivedAt = timestampWithTimeZone("received_at")
    override val primaryKey = PrimaryKey(eventId)
}

object Teachers : Table("teachers") {
    val teacherId = text("teacher_id")
    val name = text("name")
    val eventId = text("event_id") references Events.eventId
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(teacherId)
}

object EnterprisePractices : Table("enterprise_practices") {
    val id = uuid("id")
    val teacherId = text("teacher_id") references Teachers.teacherId
    val company = text("company")
    val startDate = date("start_date")
    val endDate = date("end_date")
    val eventId = text("event_id") references Events.eventId
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId)
    }
}

object Certifications : Table("certifications") {
    val id = uuid("id")
    val teacherId = text("teacher_id") references Teachers.teacherId
    val certCode = text("cert_code")
    val issuedDate = date("issued_date")
    val expiryDate = date("expiry_date").nullable()
    val eventId = text("event_id") references Events.eventId
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId)
    }
}

object CertificationRevocations : Table("certification_revocations") {
    val id = uuid("id")
    val certCode = text("cert_code")
    val effectiveDate = date("effective_date")
    val reason = text("reason").nullable()
    val eventId = text("event_id") references Events.eventId
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId)
    }
}

object IndustryProjects : Table("industry_projects") {
    val id = uuid("id")
    val teacherId = text("teacher_id") references Teachers.teacherId
    val projectName = text("project_name")
    val role = text("role")
    val acceptedByEnterprise = bool("accepted_by_enterprise")
    val acceptanceDate = date("acceptance_date").nullable()
    val eventId = text("event_id") references Events.eventId
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId)
    }
}

object CourseMatrices : Table("course_matrices") {
    val id = uuid("id")
    val courseId = text("course_id")
    val version = integer("version")
    val effectiveFrom = date("effective_from")
    val requirements = jsonb<Requirements>(
        "requirements",
        { tablesJson.encodeToString(Requirements.serializer(), it) },
        { tablesJson.decodeFromString(Requirements.serializer(), it) },
    )
    val eventId = text("event_id") references Events.eventId
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(eventId)
        uniqueIndex(courseId, version)
    }
}

object Decisions : Table("decisions") {
    val decisionId = uuid("decision_id")
    val teacherId = text("teacher_id") references Teachers.teacherId
    val courseId = text("course_id")
    val asOf = date("as_of")
    val matrixVersion = integer("matrix_version")
    val qualified = bool("qualified")
    val items = jsonb<List<DecisionItem>>(
        "items",
        { tablesJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(DecisionItem.serializer()), it) },
        { tablesJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(DecisionItem.serializer()), it) },
    )
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(decisionId)

    init {
        uniqueIndex(teacherId, courseId, asOf)
    }
}
