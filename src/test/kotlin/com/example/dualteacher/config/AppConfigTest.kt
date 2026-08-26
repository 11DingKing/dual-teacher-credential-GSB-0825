package com.example.dualteacher.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppConfigTest {

    @Test
    fun `jdbc url is used as-is`() {
        val parsed = AppConfig.parseDatabaseUrl("jdbc:postgresql://db.internal:5432/app?ssl=true")
        assertEquals("jdbc:postgresql://db.internal:5432/app?ssl=true", parsed.jdbcUrl)
        assertNull(parsed.user)
        assertNull(parsed.password)
    }

    @Test
    fun `postgres style url is converted with credentials`() {
        val parsed = AppConfig.parseDatabaseUrl("postgresql://alice:secret@db.internal:6543/app")
        assertEquals("jdbc:postgresql://db.internal:6543/app", parsed.jdbcUrl)
        assertEquals("alice", parsed.user)
        assertEquals("secret", parsed.password)
    }

    @Test
    fun `postgres style url without port defaults to 5432`() {
        val parsed = AppConfig.parseDatabaseUrl("postgres://localhost/app")
        assertEquals("jdbc:postgresql://localhost:5432/app", parsed.jdbcUrl)
    }

    @Test
    fun `env overrides credentials`() {
        val config = AppConfig.fromEnv(
            mapOf(
                "DATABASE_URL" to "postgresql://alice:secret@db:5432/app",
                "DATABASE_USER" to "bob",
                "PORT" to "9090",
            )
        )
        assertEquals("bob", config.dbUser)
        assertEquals("secret", config.dbPassword)
        assertEquals(9090, config.port)
    }
}
