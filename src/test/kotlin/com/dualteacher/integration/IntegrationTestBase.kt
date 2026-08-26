package com.dualteacher.integration

import com.dualteacher.config.DatabaseConfig
import com.dualteacher.config.DatabaseFactory
import com.dualteacher.config.dbQuery
import com.dualteacher.module
import com.dualteacher.persistence.DecisionRepository
import com.dualteacher.persistence.DecisionsTable
import com.dualteacher.persistence.EventStore
import com.dualteacher.persistence.EventsTable
import com.dualteacher.service.EventProjector
import com.dualteacher.service.QualificationEvaluator
import com.dualteacher.service.QualificationService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.BufferedReader
import java.io.InputStreamReader

object TestDatabase {
    private const val CONTAINER_NAME = "dualteacher-test-db"
    private const val POSTGRES_IMAGE = "postgres:16-alpine"
    private const val DB_NAME = "dualteacher_test"
    private const val DB_USER = "test"
    private const val DB_PASSWORD = "test"
    private const val DB_PORT = "15432"

    val jdbcUrl: String = "jdbc:postgresql://localhost:$DB_PORT/$DB_NAME"
    var initialized = false
        private set

    fun ensureStarted() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            runCommand("docker", "rm", "-f", CONTAINER_NAME)
            val output = runCommand(
                "docker", "run", "-d",
                "--name", CONTAINER_NAME,
                "-e", "POSTGRES_DB=$DB_NAME",
                "-e", "POSTGRES_USER=$DB_USER",
                "-e", "POSTGRES_PASSWORD=$DB_PASSWORD",
                "-p", "$DB_PORT:5432",
                POSTGRES_IMAGE
            )
            if (output.isBlank() || output.startsWith("Error") || output.startsWith("docker:")) {
                throw IllegalStateException("Failed to start PostgreSQL container: $output")
            }

            waitForPostgresReady()

            DatabaseFactory.resetForTesting()
            DatabaseFactory.init(
                DatabaseConfig(
                    url = jdbcUrl,
                    user = DB_USER,
                    password = DB_PASSWORD,
                    maximumPoolSize = 10
                )
            )

            Runtime.getRuntime().addShutdownHook(Thread {
                runCommand("docker", "rm", "-f", CONTAINER_NAME)
            })

            initialized = true
        }
    }

    private fun waitForPostgresReady() {
        repeat(30) {
            try {
                val result = runCommand(
                    "docker", "exec", CONTAINER_NAME,
                    "pg_isready", "-U", DB_USER, "-d", DB_NAME
                )
                if (result.contains("accepting connections")) return
            } catch (_: Exception) {
            }
            Thread.sleep(1000)
        }
        throw IllegalStateException("PostgreSQL container did not become ready in time")
    }

    private fun runCommand(vararg command: String): String {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
        process.waitFor()
        return output.trim()
    }
}

abstract class IntegrationTestBase {

    companion object {
        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            TestDatabase.ensureStarted()
        }
    }

    protected val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    protected val eventStore = EventStore()
    protected val decisionRepository = DecisionRepository()
    protected val projector = EventProjector()
    protected val evaluator = QualificationEvaluator()
    protected val service = QualificationService(eventStore, projector, evaluator, decisionRepository)

    @BeforeEach
    fun cleanDatabase() {
        runBlocking {
            dbQuery {
                DecisionsTable.deleteAll()
                EventsTable.deleteAll()
            }
        }
    }

    protected fun apiTest(block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        System.setProperty("JDBC_DATABASE_URL", TestDatabase.jdbcUrl)
        System.setProperty("DATABASE_USER", "test")
        System.setProperty("DATABASE_PASSWORD", "test")

        testApplication {
            application {
                module()
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(this@IntegrationTestBase.json)
                }
            }
            block(client)
        }
    }
}
