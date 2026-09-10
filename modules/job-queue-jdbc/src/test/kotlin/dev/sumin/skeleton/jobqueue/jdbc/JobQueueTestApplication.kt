package dev.sumin.skeleton.jobqueue.jdbc

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootApplication
class JobQueueTestApplication

@TestConfiguration(proxyBeanMethods = false)
class JobQueueTestcontainers {
    @Bean
    @ServiceConnection
    fun mysql(): MySQLContainer = MySQLContainer(DockerImageName.parse("mysql:8.4"))
}
