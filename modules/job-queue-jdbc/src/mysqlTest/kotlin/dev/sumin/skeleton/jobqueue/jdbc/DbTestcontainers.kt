package dev.sumin.skeleton.jobqueue.jdbc

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class DbTestcontainers {
    @Bean
    @ServiceConnection
    fun db(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
