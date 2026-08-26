package com.dualteacher.integration

import com.dualteacher.db.DatabaseFactory
import com.dualteacher.db.DbConfig
import com.dualteacher.db.DecisionsTable
import com.dualteacher.db.EventsTable
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager

/**
 * Provisions a dedicated scratch PostgreSQL database for the integration suite, runs Flyway
 * migrations against it, and tears it down afterwards. The admin/base connection is taken
 * from TEST_DATABASE_URL or DATABASE_URL, defaulting to a local libpq URL.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IntegrationTestBase {

    protected lateinit var factory: DatabaseFactory
    private lateinit var scratchDbName: String
    private lateinit var adminJdbcUrl: String

    @BeforeAll
    fun setUpDatabase() {
        val baseUrl = System.getenv("TEST_DATABASE_URL")
            ?: System.getenv("DATABASE_URL")
            ?: "postgresql://localhost:5432/postgres"
        val baseCfg = DbConfig.fromDatabaseUrl(baseUrl)

        // Derive an admin connection (to the maintenance DB) to create the scratch DB.
        adminJdbcUrl = baseCfg.jdbcUrl.replace(Regex("/[^/?]+(\\?.*)?$")) { m ->
            "/postgres" + (m.groupValues.getOrNull(1) ?: "")
        }
        scratchDbName = "dtc_test_" + System.nanoTime()

        DriverManager.getConnection(adminJdbcUrl, baseCfg.user, baseCfg.password).use { conn ->
            conn.createStatement().use { it.execute("CREATE DATABASE \"$scratchDbName\"") }
        }

        val scratchJdbc = adminJdbcUrl.replace(Regex("/postgres(\\?.*)?$")) { m ->
            "/$scratchDbName" + (m.groupValues.getOrNull(1) ?: "")
        }
        factory = DatabaseFactory(DbConfig(scratchJdbc, baseCfg.user, baseCfg.password))
        factory.connect()
    }

    @AfterAll
    fun tearDownDatabase() {
        factory.close()
        val baseCfg = DbConfig(adminJdbcUrl, System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD"))
        DriverManager.getConnection(adminJdbcUrl, baseCfg.user, baseCfg.password).use { conn ->
            conn.createStatement().use {
                it.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$scratchDbName'",
                )
            }
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$scratchDbName\"") }
        }
    }

    /** Empties both tables between tests for isolation. */
    protected fun cleanDatabase() = transaction {
        DecisionsTable.deleteAll()
        EventsTable.deleteAll()
    }
}
