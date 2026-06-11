package dev.sumin.skeleton.config.aws.ssm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.assertj.core.api.Assertions.assertThat
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.support.SpringFactoriesLoader

class AwsSsmEnvironmentPostProcessorTest {
    @Test
    @Suppress("DEPRECATION")
    fun `spring factories entry has no arg constructor`() {
        val factoryName = SpringFactoriesLoader
            .loadFactoryNames(EnvironmentPostProcessor::class.java, javaClass.classLoader)
            .single { it == AwsSsmEnvironmentPostProcessor::class.java.name }
        val processor = Class.forName(factoryName)
            .getDeclaredConstructor()
            .newInstance()

        assertThat(processor).isInstanceOf(AwsSsmEnvironmentPostProcessor::class.java)
    }

    @Test
    fun `disabled mode does nothing`() {
        val environment = environment(
            "skeleton.config.aws.ssm.enabled" to "false",
            "skeleton.config.aws.ssm.credential-profile" to "skeleton-dev",
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "dev",
        )
        val client = RecordingSsmParameterClient(emptyMap())

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertNull(environment.getProperty("skeleton.auth.jwt.secret"))
        assertThat(client.requestedPaths).isEmpty()
    }

    @Test
    fun `local without credential profile does nothing by default`() {
        val environment = environment(
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "local",
        )
        val client = RecordingSsmParameterClient(emptyMap())

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertNull(environment.getProperty("skeleton.auth.jwt.secret"))
        assertThat(client.requestedPaths).isEmpty()
    }

    @Test
    fun `local with credential profile loads ssm by default`() {
        val environment = environment(
            "skeleton.config.aws.ssm.credential-profile" to "skeleton-dev",
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "local",
        )
        val client = RecordingSsmParameterClient(
            mapOf(
                "/kotlin-skeleton/dev/api/" to mapOf(
                    "/kotlin-skeleton/dev/api/skeleton.auth.jwt.secret" to "dev-secret",
                ),
            ),
        )

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals(listOf("/kotlin-skeleton/dev/api/"), client.requestedPaths)
        assertEquals("dev-secret", environment.getProperty("skeleton.auth.jwt.secret"))
    }

    @Test
    fun `loads paths in order and later paths override earlier values`() {
        val environment = environment(
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/common/",
            "skeleton.config.aws.ssm.paths[1]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "dev",
        )
        val client = RecordingSsmParameterClient(
            mapOf(
                "/kotlin-skeleton/dev/common/" to mapOf(
                    "/kotlin-skeleton/dev/common/skeleton.auth.jwt.secret" to "common-secret",
                    "/kotlin-skeleton/dev/common/spring.datasource.password" to "common-db",
                ),
                "/kotlin-skeleton/dev/api/" to mapOf(
                    "/kotlin-skeleton/dev/api/skeleton.auth.jwt.secret" to "api-secret",
                ),
            ),
        )

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals(listOf("/kotlin-skeleton/dev/common/", "/kotlin-skeleton/dev/api/"), client.requestedPaths)
        assertEquals("api-secret", environment.getProperty("skeleton.auth.jwt.secret"))
        assertEquals("common-db", environment.getProperty("spring.datasource.password"))
    }

    @Test
    fun `replaces profile placeholder in configured paths`() {
        val environment = environment(
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/{profile}/api/",
            "spring.profiles.active" to "prod",
        )
        val client = RecordingSsmParameterClient(
            mapOf(
                "/kotlin-skeleton/prod/api/" to mapOf(
                    "/kotlin-skeleton/prod/api/skeleton.auth.jwt.secret" to "prod-secret",
                ),
            ),
        )

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals(listOf("/kotlin-skeleton/prod/api/"), client.requestedPaths)
        assertEquals("prod-secret", environment.getProperty("skeleton.auth.jwt.secret"))
    }

    @Test
    fun `environment variables override ssm values`() {
        val environment = environment(
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "skeleton.auth.jwt.secret" to "env-secret",
            "spring.profiles.active" to "dev",
        )
        val client = RecordingSsmParameterClient(
            mapOf(
                "/kotlin-skeleton/dev/api/" to mapOf(
                    "/kotlin-skeleton/dev/api/skeleton.auth.jwt.secret" to "ssm-secret",
                ),
            ),
        )

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { client })
            .postProcessEnvironment(environment, SpringApplication())

        assertEquals("env-secret", environment.getProperty("skeleton.auth.jwt.secret"))
    }

    @Test
    fun `fail fast true throws with login guidance when ssm load fails`() {
        val environment = environment(
            "skeleton.config.aws.ssm.fail-fast" to "true",
            "skeleton.config.aws.ssm.credential-profile" to "skeleton-dev",
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "dev",
        )
        val error = assertFailsWith<AwsSsmConfigException> {
            AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { ThrowingSsmParameterClient("no credentials") })
                .postProcessEnvironment(environment, SpringApplication())
        }

        val message = error.message.orEmpty()
        assertThat(message).contains("AWS SSM configuration failed")
        assertThat(message).contains("aws sso login --profile skeleton-dev")
        assertThat(message).contains("AWS_PROFILE=skeleton-dev SPRING_PROFILES_ACTIVE=dev")
    }

    @Test
    fun `fail fast false keeps startup going when ssm load fails`() {
        val environment = environment(
            "skeleton.config.aws.ssm.fail-fast" to "false",
            "skeleton.config.aws.ssm.paths[0]" to "/kotlin-skeleton/dev/api/",
            "spring.profiles.active" to "dev",
        )

        AwsSsmEnvironmentPostProcessor(SsmParameterClientFactory { ThrowingSsmParameterClient("network") })
            .postProcessEnvironment(environment, SpringApplication())

        assertNull(environment.getProperty("skeleton.auth.jwt.secret"))
    }

    private fun environment(vararg values: Pair<String, String>): StandardEnvironment {
        val environment = StandardEnvironment()
        environment.propertySources.addFirst(MapPropertySource("test", values.toMap()))
        environment.merge(environment)
        return environment
    }

    private class RecordingSsmParameterClient(
        private val parametersByPath: Map<String, Map<String, String>>,
    ) : SsmParameterClient {
        val requestedPaths = mutableListOf<String>()

        override fun getParametersByPath(path: String): Map<String, String> {
            requestedPaths += path
            return parametersByPath[path].orEmpty()
        }
    }

    private class ThrowingSsmParameterClient(
        private val message: String,
    ) : SsmParameterClient {
        override fun getParametersByPath(path: String): Map<String, String> {
            throw IllegalStateException(message)
        }
    }
}
