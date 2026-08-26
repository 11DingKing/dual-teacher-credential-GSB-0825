package com.gsb.dualteacher.db

import com.gsb.dualteacher.domain.QualificationEvaluator
import com.gsb.dualteacher.domain.StoredEvent
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant

data class NewEvent(
    val eventId: java.util.UUID,
    val eventType: String,
    val teacherId: String?,
    val occurredAt: Instant,
    val payloadJson: String,
)

class EventRepository {

    /**
     * 幂等写入：event_id 已存在则跳过（重试安全）。
     * 先查后插仍可能并发撞主键，捕获唯一约束冲突后按已存在处理。
     * 返回 true 表示新写入，false 表示重复事件。
     */
    suspend fun insertIfAbsent(event: NewEvent): Boolean = newSuspendedTransaction {
        val exists = Events.selectAll()
            .where { Events.eventId eq event.eventId }
            .count() > 0
        if (exists) return@newSuspendedTransaction false

        try {
            val now = Instant.now()
            Events.insert {
                it[Events.eventId] = event.eventId
                it[Events.eventType] = event.eventType
                it[Events.teacherId] = event.teacherId
                it[Events.occurredAt] = event.occurredAt
                it[Events.payload] = event.payloadJson
                it[Events.receivedAt] = now
                it[Events.createdAt] = now
            }
            true
        } catch (e: Exception) {
            if (isUniqueViolation(e)) false else throw e
        }
    }

    /** 读取某教师的全部事件 + 全局事件（教师为空，如能力矩阵发布）。 */
    suspend fun findForTeacher(teacherId: String): List<StoredEvent> = newSuspendedTransaction {
        Events.selectAll()
            .where { (Events.teacherId eq teacherId) or Events.teacherId.isNull() }
            .orderBy(Events.occurredAt)
            .map { row ->
                StoredEvent(
                    eventId = row[Events.eventId],
                    eventType = row[Events.eventType],
                    teacherId = row[Events.teacherId],
                    occurredAt = row[Events.occurredAt],
                    payload = QualificationEvaluator.json.parseToJsonElement(row[Events.payload]) as JsonObject,
                )
            }
    }
}
