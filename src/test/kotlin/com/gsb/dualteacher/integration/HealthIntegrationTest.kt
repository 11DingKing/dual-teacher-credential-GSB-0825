package com.gsb.dualteacher.integration

import com.gsb.dualteacher.web.HealthResponse
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HealthIntegrationTest : IntegrationTestBase() {

    @BeforeEach
    fun clean() = runBlocking { clearTables() }

    @Test
    fun `存活检查返回 UP`() = withApp {
        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("UP", response.body<HealthResponse>().status)
    }

    @Test
    fun `就绪检查探测数据库`() = withApp {
        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<HealthResponse>()
        assertEquals("UP", body.status)
        assertEquals("UP", body.database)
    }

    @Test
    fun `OpenAPI 规范可访问`() = withApp {
        val response = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"openapi\""))
    }

    @Test
    fun `未知 decision_id 返回 404`() = withApp {
        val response = decision(java.util.UUID.randomUUID().toString())
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
