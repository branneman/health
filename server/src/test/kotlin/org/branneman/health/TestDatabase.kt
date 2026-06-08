package org.branneman.health

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

object TestDatabase {
    private val container = PostgreSQLContainer("postgres:16-alpine").apply {
        withDatabaseName("health_test")
        start()
    }

    val dataSource: DataSource by lazy {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = container.jdbcUrl
            username = container.username
            password = container.password
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
        })
        Flyway.configure().dataSource(ds).load().migrate()
        ds
    }
}
