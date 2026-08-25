package com.dualteacher.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import java.net.URI
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
    val maximumPoolSize: Int = 10
)

object DatabaseFactory {

    private lateinit var dataSource: DataSource
    @Volatile private var initialized = false

    fun init(config: DatabaseConfig): DataSource {
        if (initialized) return dataSource

        synchronized(this) {
            if (initialized) return dataSource

            val (jdbcUrl, parsedUser, parsedPassword) = parseUrl(config.url)
            val user = config.user.ifBlank { parsedUser }
            val password = config.password.ifBlank { parsedPassword }

            logger.info { "Initializing database connection to $jdbcUrl (user=$user)" }

            val hikariConfig = HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                if (user.isNotBlank()) this.username = user
                if (password.isNotBlank()) this.password = password
                this.driverClassName = "org.postgresql.Driver"
                this.maximumPoolSize = config.maximumPoolSize
                this.isAutoCommit = false
                this.transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                this.validate()
            }

            dataSource = HikariDataSource(hikariConfig)

            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            logger.info { "Database migrations completed successfully" }

            Database.connect(dataSource)
            initialized = true

            return dataSource
        }
    }

    fun getDataSource(): DataSource = dataSource

    internal fun resetForTesting() {
        if (::dataSource.isInitialized && dataSource is HikariDataSource) {
            (dataSource as HikariDataSource).close()
        }
        initialized = false
    }

    private fun parseUrl(rawUrl: String): Triple<String, String, String> {
        return when {
            rawUrl.startsWith("jdbc:postgresql://") -> {
                Triple(rawUrl, "", "")
            }
            rawUrl.startsWith("postgresql://") || rawUrl.startsWith("postgres://") -> {
                val uri = URI.create(rawUrl)
                val host = uri.host ?: "localhost"
                val port = if (uri.port > 0) uri.port else 5432
                val dbName = uri.path?.removePrefix("/")?.takeIf { it.isNotBlank() }
                    ?: "postgres"
                val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"
                val userInfo = uri.userInfo.orEmpty()
                val user = userInfo.substringBefore(":", "")
                val password = userInfo.substringAfter(":", "")
                Triple(jdbcUrl, user, password)
            }
            else -> Triple(rawUrl, "", "")
        }
    }
}

suspend fun <T> dbQuery(block: () -> T): T = withContext(Dispatchers.IO) {
    transaction { block() }
}
