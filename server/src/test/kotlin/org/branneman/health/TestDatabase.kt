package org.branneman.health

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import javax.sql.DataSource

object TestDatabase {
    val dataSource: DataSource by lazy {
        val ds = HikariDataSource(HikariConfig().apply {
            val port = System.getenv("POSTGRES_PORT") ?: "5432"
            jdbcUrl  = System.getenv("TEST_DATABASE_URL")      ?: "jdbc:postgresql://localhost:$port/health_test"
            username = System.getenv("TEST_POSTGRES_USER")     ?: System.getenv("POSTGRES_USER")     ?: "health"
            password = System.getenv("TEST_POSTGRES_PASSWORD") ?: System.getenv("POSTGRES_PASSWORD") ?: "health"
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 5
        })
        Flyway.configure().dataSource(ds).load().migrate()
        ds
    }
}
