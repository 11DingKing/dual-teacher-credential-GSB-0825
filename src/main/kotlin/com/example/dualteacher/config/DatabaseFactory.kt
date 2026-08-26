package com.example.dualteacher.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object DatabaseFactory {

    /** 先执行 Flyway 迁移，再建立连接池。 */
    fun init(config: AppConfig, poolSize: Int = 10): Database {
        Flyway.configure()
            .dataSource(config.jdbcUrl, config.dbUser, config.dbPassword)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val dataSource = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            config.dbUser?.let { username = it }
            config.dbPassword?.let { password = it }
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = poolSize
        })
        return Database.connect(dataSource)
    }
}
