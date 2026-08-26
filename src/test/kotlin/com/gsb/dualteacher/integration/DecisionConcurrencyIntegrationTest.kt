package com.gsb.dualteacher.integration

import com.gsb.dualteacher.Fixtures
import com.gsb.dualteacher.db.Decisions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class DecisionConcurrencyIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun clean() = runBlocking { clearTables() }

    @Test
    fun `并发创建同一决定只落一份快照并返回同一 decision_id`() = runBlocking {
        Fixtures.t1Timeline().forEach {
            services.eventService.ingest(Fixtures.envelope(it))
        }
        val asOf = LocalDate.parse("2026-04-30")

        val snapshots = coroutineScope {
            (1..8).map {
                async(Dispatchers.IO) {
                    services.qualificationService.evaluate(Fixtures.T1, Fixtures.COURSE, asOf)
                }
            }.awaitAll()
        }

        val ids = snapshots.map { it.decisionId }.distinct()
        assertEquals(1, ids.size, "并发请求应全部返回同一个 decision_id，实际：$ids")

        val rowCount = newSuspendedTransaction {
            Decisions.selectAll().where { Decisions.teacherId eq Fixtures.T1 }.count()
        }
        assertEquals(1L, rowCount, "decisions 表中只应有一份快照")

        // 串行再查一次仍然命中同一份
        val again = services.qualificationService.evaluate(Fixtures.T1, Fixtures.COURSE, asOf)
        assertEquals(ids.single(), again.decisionId)
    }
}
