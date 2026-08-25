package com.dualteacher.repo

import com.dualteacher.app.AppJson
import com.dualteacher.db.DecisionsTable
import com.dualteacher.domain.QualificationDecision
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Stores and retrieves immutable qualification decisions.
 *
 * A decision id is a deterministic hash of (teacher, course, as_of), so the same query
 * always maps to the same row. Persisting uses `INSERT ... ON CONFLICT DO NOTHING` and then
 * reads back the stored row: if two requests race, exactly one row is written and both
 * callers observe that single immutable snapshot. Stored decisions are never updated, so
 * material recorded later cannot rewrite a past decision.
 */
class DecisionRepository {

    /** Deterministic decision id for a query coordinate. */
    fun decisionIdFor(teacherId: String, courseId: String, asOf: LocalDate): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$teacherId|$courseId|$asOf".toByteArray())
        return "dec_" + digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    /**
     * Persists [decision] if absent, then returns the authoritative stored snapshot (which may
     * be a pre-existing one written by a concurrent request).
     */
    fun saveIfAbsent(decision: QualificationDecision): QualificationDecision = transaction {
        DecisionsTable.insertIgnore {
            it[decisionId] = decision.decisionId
            it[teacherId] = decision.teacherId
            it[courseId] = decision.courseId
            it[asOf] = decision.asOf
            it[qualified] = decision.qualified
            it[matrixVersion] = decision.matrixVersion
            it[decidedAt] = OffsetDateTime.ofInstant(decision.decidedAt, ZoneOffset.UTC)
            it[snapshot] = AppJson.encodeToString(QualificationDecision.serializer(), decision)
        }
        readByIdInTx(decision.decisionId)
            ?: error("decision ${decision.decisionId} vanished after insert")
    }

    /** Fetches a stored decision by id, or null if none exists. */
    fun findById(decisionId: String): QualificationDecision? = transaction { readByIdInTx(decisionId) }

    private fun readByIdInTx(decisionId: String): QualificationDecision? =
        DecisionsTable.selectAll()
            .where { DecisionsTable.decisionId eq decisionId }
            .limit(1)
            .firstOrNull()
            ?.let { AppJson.decodeFromString(QualificationDecision.serializer(), it[DecisionsTable.snapshot]) }
}
