package com.dualteacher.persistence

import com.dualteacher.config.dbQuery
import com.dualteacher.domain.DecisionSnapshot
import com.dualteacher.domain.QualificationResult
import com.dualteacher.domain.QualificationStatus
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID

data class StoredDecision(
    val decisionId: UUID,
    val teacherId: String,
    val courseId: String,
    val asOf: LocalDate,
    val overallResult: QualificationStatus,
    val snapshot: DecisionSnapshot
)

class DecisionRepository {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createOrGet(result: QualificationResult): StoredDecision {
        val snapshot = result.toSnapshot()
        val snapshotJson = json.encodeToJsonElement(DecisionSnapshot.serializer(), snapshot)
        val decisionId = UUID.randomUUID()

        return try {
            dbQuery {
                DecisionsTable.insert {
                    it[id] = decisionId
                    it[teacherId] = result.teacherId
                    it[courseId] = result.courseId
                    it[asOf] = result.asOf
                    it[overallResult] = result.overallResult.name
                    it[DecisionsTable.snapshot] = snapshotJson
                }
                StoredDecision(
                    decisionId = decisionId,
                    teacherId = result.teacherId,
                    courseId = result.courseId,
                    asOf = result.asOf,
                    overallResult = result.overallResult,
                    snapshot = snapshot
                )
            }
        } catch (e: Exception) {
            if (isDuplicateKey(e)) {
                dbQuery {
                    DecisionsTable.select {
                        (DecisionsTable.teacherId eq result.teacherId) and
                            (DecisionsTable.courseId eq result.courseId) and
                            (DecisionsTable.asOf eq result.asOf)
                    }.firstOrNull()?.toStoredDecision()
                        ?: throw IllegalStateException(
                            "Concurrent insert failed but no existing decision found for " +
                                "${result.teacherId}/${result.courseId}/${result.asOf}", e
                        )
                }
            } else {
                throw e
            }
        }
    }

    suspend fun findById(decisionId: UUID): StoredDecision? = dbQuery {
        DecisionsTable.select { DecisionsTable.id eq decisionId }
            .firstOrNull()
            ?.toStoredDecision()
    }

    suspend fun findByScope(
        teacherId: String,
        courseId: String,
        asOf: LocalDate
    ): StoredDecision? = dbQuery {
        DecisionsTable.select {
            (DecisionsTable.teacherId eq teacherId) and
                (DecisionsTable.courseId eq courseId) and
                (DecisionsTable.asOf eq asOf)
        }.firstOrNull()?.toStoredDecision()
    }

    private fun isDuplicateKey(e: Throwable): Boolean {
        var cause: Throwable? = e
        val visited = mutableSetOf<Throwable>()
        while (cause != null && cause !in visited) {
            visited.add(cause)
            if (cause is SQLException) {
                if (cause.sqlState == "23505") return true
            }
            if (cause.message?.contains("uq_decision_scope", ignoreCase = true) == true) return true
            if (cause.message?.contains("duplicate key", ignoreCase = true) == true) return true
            cause = cause.cause
        }
        return false
    }

    private fun ResultRow.toStoredDecision(): StoredDecision {
        val snapshotJson = this[DecisionsTable.snapshot]
        val snapshot = json.decodeFromJsonElement(DecisionSnapshot.serializer(), snapshotJson)
        return StoredDecision(
            decisionId = this[DecisionsTable.id].value,
            teacherId = this[DecisionsTable.teacherId],
            courseId = this[DecisionsTable.courseId],
            asOf = this[DecisionsTable.asOf],
            overallResult = QualificationStatus.valueOf(this[DecisionsTable.overallResult]),
            snapshot = snapshot
        )
    }
}
