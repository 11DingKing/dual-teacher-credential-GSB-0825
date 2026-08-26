package com.gsb.dualteacher.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import javax.sql.DataSource

data class DataSourceConfig(
    val jdbcUrl: String,
    val user: String?,
    val password: String?,
)

/**
 * 解析数据库连接配置。
 * 支持：
 *  - JDBC URL：jdbc:postgresql://host:5432/dbname
 *  - Heroku 风格：postgres://user:pass@host:5432/dbname
 * 默认：jdbc:postgresql://localhost:5432/dualteacher，用户/密码取 DB_USER/DB_PASSWORD。
 */
fun resolveDataSourceConfig(
    databaseUrl: String? = System.getenv("DATABASE_URL"),
    dbUser: String? = System.getenv("DB_USER"),
    dbPassword: String? = System.getenv("DB_PASSWORD"),
): DataSourceConfig {
    if (databaseUrl.isNullOrBlank()) {
        return DataSourceConfig(
            jdbcUrl = "jdbc:postgresql://localhost:5432/dualteacher",
            user = dbUser ?: "dualteacher",
            password = dbPassword ?: "dualteacher",
        )
    }
    if (databaseUrl.startsWith("jdbc:")) {
        return DataSourceConfig(databaseUrl, dbUser, dbPassword)
    }
    val uri = java.net.URI(databaseUrl)
    val jdbcUrl = "jdbc:postgresql://${uri.host}${uri.port.takeIf { it > 0 }?.let { ":$it" } ?: ""}${uri.path}"
    val user = uri.userInfo?.substringBefore(':') ?: dbUser
    val password = uri.userInfo?.substringAfter(':', "")?.takeIf { it.isNotEmpty() } ?: dbPassword
    return DataSourceConfig(jdbcUrl, user, password)
}

object DatabaseFactory {
    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun connect(config: DataSourceConfig): DataSource {
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.user, config.password)
            .baselineOnMigrate(true)
            .load()
            .migrate()
            .also {
                log.info("flyway migrated to version {} ({} migrations applied)", it.targetSchemaVersion, it.migrationsExecuted)
            }

        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.jdbcUrl
                username = config.user
                password = config.password
                maximumPoolSize = 10
                isAutoCommit = true
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
            },
        )
        Database.connect(dataSource)
        log.info("database connected: {}", config.jdbcUrl)
        return dataSource
    }
}
