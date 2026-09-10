package dev.sumin.skeleton.persistence.jooq

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootApplication
class JooqTestApplication

@TestConfiguration(proxyBeanMethods = false)
class JooqTestcontainers {
    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
