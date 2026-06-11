package dev.sumin.skeleton.api

import dev.sumin.skeleton.common.config.ConfigValidationProperties
import dev.sumin.skeleton.common.config.MapPropertyResolver
import dev.sumin.skeleton.common.config.RequiredConfigValidator
import dev.sumin.skeleton.config.aws.ssm.AwsSsmEnvironmentPostProcessor
import dev.sumin.skeleton.config.aws.ssm.SsmParameterClient
import dev.sumin.skeleton.config.aws.ssm.SsmParameterClientFactory
import kotlin.test.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class AwsSsmProfileConfigurationIntegrationTest {
    @Test
    fun `ssm values satisfy required config validation before application startup`() {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf(
                    "spring.profiles.active" to "dev",
                    "skeleton.config.aws.ssm.enabled" to "true",
                    "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/{profile}/api/",
                ),
            ),
        )
        val client = SsmParameterClient {
            mapOf(
                "/kotlin-skeleton/dev/api/skeleton.auth.jwt.secret" to "dev-ssm-secret-32-bytes-change",
                "/kotlin-skeleton/dev/api/spring.datasource.password" to "dev-ssm-db-password",
            )
        }

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        RequiredConfigValidator.validate(
            activeProfiles = environment.activeProfiles.toSet(),
            properties = ConfigValidationProperties(
                requirements = listOf(
                    ConfigValidationProperties.Requirement(
                        property = "skeleton.auth.jwt.secret",
                        env = "JWT_SECRET",
                        ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                        profiles = setOf("dev"),
                        secret = true,
                    ),
                    ConfigValidationProperties.Requirement(
                        property = "spring.datasource.password",
                        env = "SPRING_DATASOURCE_PASSWORD",
                        ssm = "/kotlin-skeleton/{profile}/spring.datasource.password",
                        profiles = setOf("dev"),
                        secret = true,
                    ),
                ),
            ),
            propertyResolver = MapPropertyResolver(
                mapOf(
                    "skeleton.auth.jwt.secret" to environment.getProperty("skeleton.auth.jwt.secret"),
                    "spring.datasource.password" to environment.getProperty("spring.datasource.password"),
                ),
            ),
        )
    }
}
