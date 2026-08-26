package com.example.dualteacher.config

import java.net.URI

/**
 * 应用配置。
 * DATABASE_URL 支持两种形式：
 *  - jdbc:postgresql://host:5432/db?user=...&password=...
 *  - postgresql://user:pass@host:5432/db （Heroku 风格）
 * 也可用 DATABASE_USER / DATABASE_PASSWORD 单独提供凭据。
 */
data class AppConfig(
    val port: Int,
    val jdbcUrl: String,
    val dbUser: String?,
    val dbPassword: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AppConfig {
            val raw = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/dualteacher"
            val parsed = parseDatabaseUrl(raw)
            return AppConfig(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                jdbcUrl = parsed.jdbcUrl,
                dbUser = env["DATABASE_USER"] ?: parsed.user,
                dbPassword = env["DATABASE_PASSWORD"] ?: parsed.password,
            )
        }

        data class Parsed(val jdbcUrl: String, val user: String?, val password: String?)

        fun parseDatabaseUrl(raw: String): Parsed {
            if (raw.startsWith("jdbc:")) return Parsed(raw, null, null)
            val uri = URI(raw)
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val userInfo = uri.userInfo
            return Parsed(
                jdbcUrl = "jdbc:postgresql://${uri.host}:$port${uri.path}$query",
                user = userInfo?.substringBefore(":"),
                password = userInfo?.substringAfter(":", "")?.ifEmpty { null },
            )
        }
    }
}
