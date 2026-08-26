package com.gsb.dualteacher.integration

import com.gsb.dualteacher.AppServices
import com.gsb.dualteacher.Fixtures
import com.gsb.dualteacher.appModule
import com.gsb.dualteacher.config.DataSourceConfig
import com.gsb.dualteacher.config.DatabaseFactory
import com.gsb.dualteacher.db.DecisionRepository
import com.gsb.dualteacher.db.Decisions
import com.gsb.dualteacher.db.EventRepository
import com.gsb.dualteacher.db.Events
import com.gsb.dualteacher.db.HealthRepository
import com.gsb.dualteacher.domain.StoredEvent
import com.gsb.dualteacher.service.EventService
import com.gsb.dualteacher.service.QualificationService
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.UUID

/**
 * 集成测试基类：H2（PostgreSQL 兼容模式）+ 同一套 Flyway 迁移 + Ktor 测试引擎。
 * 不依赖 Docker，./gradlew test 可直接运行。
 */
abstract class IntegrationTestBase {

    companion object {
        val services: AppServices

        init {
            val dbName = "it_${UUID.randomUUID().toString().replace("-", "")}"
            val config = DataSourceConfig(
                jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                user = "sa",
                password = "",
            )
            DatabaseFactory.connect(config)
            services = AppServices(
                eventService = EventService(EventRepository()),
                qualificationService = QualificationService(EventRepository(), DecisionRepository()),
                healthRepository = HealthRepository(),
            )
        }

        suspend fun clearTables() {
            newSuspendedTransaction {
                exec("DELETE FROM decisions")
                exec("DELETE FROM events")
            }
        }
    }

    protected fun withApp(block: suspend HttpClientTestScope.() -> Unit) = testApplication {
        application { appModule(services) }
        val client = createClient {
            install(ContentNegotiation) { json() }
        }
        HttpClientTestScope(client).block()
    }

    protected class HttpClientTestScope(val client: HttpClient) {
        suspend fun postEvent(event: StoredEvent): HttpResponse =
            client.post("/api/v1/events") {
                contentType(ContentType.Application.Json)
                setBody(Fixtures.envelope(event))
            }

        suspend fun postEvents(events: List<StoredEvent>) {
            events.forEach { postEvent(it) }
        }

        suspend fun qualification(teacher: String, course: String, asOf: String): HttpResponse =
            client.get("/api/v1/teachers/$teacher/qualification?course=$course&asOf=$asOf")

        suspend fun decision(decisionId: String): HttpResponse =
            client.get("/api/v1/decisions/$decisionId")
    }
}
