package com.gsb.dualteacher.db

import com.gsb.dualteacher.domain.DecisionSnapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DecisionRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 同一 (教师, 课程, 基准日) 的创建按 key 串行，使并发请求在唯一约束触发前
     * 就收敛到同一事务里完成“先查后插”；跨进程/极端竞态再由数据库唯一约束兜底
     * （冲突后在新事务中回查已落库的快照）。
     */
    private val keyLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun saveOrGet(snapshot: DecisionSnapshot): DecisionSnapshot {
        val asOf = LocalDate.parse(snapshot.asOf)
        val key = "${snapshot.teacherId}|${snapshot.courseCode}|$asOf"
        val mutex = keyLocks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            findSnapshot(snapshot.teacherId, snapshot.courseCode, asOf)?.let { return@withLock it }
            try {
                insertSnapshot(snapshot)
                snapshot
            } catch (e: Exception) {
                if (isUniqueViolation(e)) {
                    findSnapshot(snapshot.teacherId, snapshot.courseCode, asOf)
                        ?: throw IllegalStateException("unique violation but decision row missing: ${snapshot.decisionId}", e)
                } else {
                    throw e
                }
            }
        }
    }

    suspend fun findById(decisionId: UUID): DecisionSnapshot? = newSuspendedTransaction {
        Decisions.selectAll()
            .where { Decisions.decisionId eq decisionId }
            .firstOrNull()
            ?.let { json.decodeFromString(DecisionSnapshot.serializer(), it[Decisions.snapshotJson]) }
    }

    private suspend fun findSnapshot(teacherId: String, courseCode: String, asOf: LocalDate): DecisionSnapshot? =
        newSuspendedTransaction {
            Decisions.selectAll()
                .where {
                    (Decisions.teacherId eq teacherId) and
                        (Decisions.courseCode eq courseCode) and
                        (Decisions.asOfDate eq asOf)
                }
                .firstOrNull()
                ?.let { json.decodeFromString(DecisionSnapshot.serializer(), it[Decisions.snapshotJson]) }
        }

    private suspend fun insertSnapshot(snapshot: DecisionSnapshot): Unit = newSuspendedTransaction {
        val decisionId = uuidOf(snapshot.decisionId)
        val asOf = LocalDate.parse(snapshot.asOf)
        val now = Instant.now()
        Decisions.insert {
            it[Decisions.decisionId] = decisionId
            it[Decisions.teacherId] = snapshot.teacherId
            it[Decisions.courseCode] = snapshot.courseCode
            it[Decisions.asOfDate] = asOf
            it[Decisions.matrixVersion] = snapshot.matrixVersion
            it[Decisions.result] = snapshot.result
            it[Decisions.snapshotJson] = json.encodeToString(DecisionSnapshot.serializer(), snapshot)
            it[Decisions.createdAt] = now
        }
    }
}
