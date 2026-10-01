package dev.sumin.skeleton.persistence.jooq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootApplication
class JooqTestApplication

@TestConfiguration(proxyBeanMethods = false)
class JooqTestcontainers {
    @Bean
    @ServiceConnection
    fun db(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
}
