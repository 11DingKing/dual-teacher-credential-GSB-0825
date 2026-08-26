package com.example.dualteacher.integration

import com.example.dualteacher.db.Decisions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 并发创建同一决定：数据库唯一约束仲裁，所有请求获得同一个 decision_id，且只落一行。 */
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+")
class DecisionConcurrencyTest : IntegrationTestBase() {

    @Test
    fun `concurrent creation of the same decision yields exactly one snapshot`() = runBlocking {
        seedStandardT1()
        val asOf = LocalDate.parse("2026-04-30")

        val results = (1..16).map {
            async(Dispatchers.IO) { qualification.decide("T1", "COURSE-1", asOf) }
        }.awaitAll()

        assertEquals(1, results.map { it.decisionId }.toSet().size, "所有并发请求必须得到同一个 decision_id")
        assertEquals(1, results.count { it.created }, "恰好一个请求创建快照")
        assertTrue(results.all { it.qualified })
        transaction(db) {
            assertEquals(1, Decisions.selectAll().count())
        }
    }
}
