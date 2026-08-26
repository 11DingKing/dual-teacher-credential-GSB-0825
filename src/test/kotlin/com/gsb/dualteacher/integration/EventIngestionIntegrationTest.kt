package com.gsb.dualteacher.integration

import com.gsb.dualteacher.Fixtures
import com.gsb.dualteacher.domain.EventEnvelope
import com.gsb.dualteacher.web.IngestResponse
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventIngestionIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun clean() = runBlocking { clearTables() }

    @Test
    fun `新事件 201 落库，重复事件 200 幂等返回`() = withApp {
        val event = Fixtures.t1Timeline().first()

        val first = postEvent(event)
        assertEquals(HttpStatusCode.Created, first.status)
        assertFalse(first.body<IngestResponse>().duplicate)

        val second = postEvent(event)
        assertEquals(HttpStatusCode.OK, second.status)
        assertTrue(second.body<IngestResponse>().duplicate)
    }

    @Test
    fun `未知事件类型返回 400`() = withApp {
        val response = client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(
                EventEnvelope(
                    eventId = java.util.UUID.randomUUID().toString(),
                    eventType = "unknown.type",
                    occurredAt = "2026-01-01",
                    payload = buildJsonObject {},
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `非法 eventId 返回 400`() = withApp {
        val event = Fixtures.t1Timeline().first().let {
            Fixtures.envelope(it).copy(eventId = "not-a-uuid")
        }
        val response = client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(event)
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `payload 与事件类型不符返回 400`() = withApp {
        val response = client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(
                EventEnvelope(
                    eventId = java.util.UUID.randomUUID().toString(),
                    eventType = "practice.recorded",
                    occurredAt = "2026-01-01",
                    payload = buildJsonObject { },
                ),
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
