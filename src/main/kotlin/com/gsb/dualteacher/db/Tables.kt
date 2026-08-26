package com.gsb.dualteacher.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.UUIDColumnType
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import java.util.UUID

object Events : Table("events") {
    val eventId = registerColumn<UUID>("event_id", UUIDColumnType())
    val eventType = varchar("event_type", 64)
    val teacherId = varchar("teacher_id", 64).nullable()
    val occurredAt = timestamp("occurred_at")
    val payload = text("payload")
    val receivedAt = timestamp("received_at")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(eventId)
}

object Decisions : Table("decisions") {
    val decisionId = registerColumn<UUID>("decision_id", UUIDColumnType())
    val teacherId = varchar("teacher_id", 64)
    val courseCode = varchar("course_code", 64)
    val asOfDate = date("as_of_date")
    val matrixVersion = varchar("matrix_version", 32).nullable()
    val result = varchar("result", 32)
    val snapshotJson = text("snapshot_json")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(decisionId)
}

/**
 * 唯一约束冲突识别：
 *  - PostgreSQL SQLState = 23505，H2 SQLState 可能为 23505/23001；
 *  - ExposedSQLException 包装了底层 JDBC 异常，需要沿 cause 链查找；
 *  - 兜底匹配两边驱动的错误消息特征。
 */
fun isUniqueViolation(e: Throwable): Boolean {
    var current: Throwable? = e
    while (current != null) {
        if (current is java.sql.SQLException && current.sqlState in setOf("23505", "23001")) {
            return true
        }
        val message = current.message
        if (message != null && UNIQUE_VIOLATION_MARKERS.any { message.contains(it, ignoreCase = true) }) {
            return true
        }
        if (current === current.cause) break
        current = current.cause
    }
    return false
}

private val UNIQUE_VIOLATION_MARKERS = listOf(
    "unique index or primary key violation",
    "duplicate key",
    "unique constraint",
    "violates unique",
)

fun uuidOf(value: String): UUID = try {
    UUID.fromString(value)
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("invalid UUID: $value", e)
}
