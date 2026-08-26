package com.dualteacher.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import javax.sql.DataSource

/**
 * Parsed database connection settings. Accepts either a JDBC URL
 * (`jdbc:postgresql://host:port/db`) or a libpq URL (`postgresql://user:pass@host:port/db`),
 * which is what operators typically export as `DATABASE_URL`.
 */
data class DbConfig(val jdbcUrl: String, val user: String?, val password: String?) {
    companion object {
        fun fromDatabaseUrl(raw: String): DbConfig {
            val trimmed = raw.trim()
            if (trimmed.startsWith("jdbc:")) {
                return DbConfig(trimmed, System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD"))
            }
            // postgresql://user:pass@host:port/dbname?params
            val uri = java.net.URI(trimmed)
            val userInfo = uri.userInfo
            val user = userInfo?.substringBefore(":")
            val password = userInfo?.substringAfter(":", "")?.ifEmpty { null }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.query?.let { "?$it" } ?: ""
            val jdbc = "jdbc:postgresql://${uri.host}:$port${uri.path}$query"
            return DbConfig(jdbc, user, password)
        }
    }
}

/** Owns the pooled [DataSource], runs migrations, and binds Exposed to the database. */
class DatabaseFactory(private val config: DbConfig) {
    lateinit var dataSource: HikariDataSource
        private set

    fun connect(): Database {
        dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                config.user?.let { username = it }
                config.password?.let { password = it }
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = 10
                isAutoCommit = false
                poolName = "dual-teacher-pool"
            },
        )
        migrate(dataSource)
        return Database.connect(dataSource)
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    private fun migrate(ds: DataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
