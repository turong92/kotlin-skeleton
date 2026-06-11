package dev.sumin.skeleton.config.aws.ssm

import org.slf4j.LoggerFactory
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class AwsSsmConfigException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class AwsSsmEnvironmentPostProcessor(
    private val clientFactory: SsmParameterClientFactory,
) : EnvironmentPostProcessor, Ordered {
    constructor() : this(AwsSdkSsmParameterClientFactory())

    private val log = LoggerFactory.getLogger(javaClass)

    override fun getOrder(): Int = Ordered.LOWEST_PRECEDENCE - 10

    override fun postProcessEnvironment(
        environment: ConfigurableEnvironment,
        application: SpringApplication,
    ) {
        val properties = Binder.get(environment)
            .bind("skeleton.config.aws.ssm", AwsSsmProperties::class.java)
            .orElse(AwsSsmProperties()) ?: AwsSsmProperties()

        if (!properties.enabled) return

        runCatching {
            loadProperties(environment, properties)
        }.onFailure { error ->
            val message = failureMessage(environment, properties, error)
            if (properties.failFast) {
                throw AwsSsmConfigException(message, error)
            }
            log.warn(message)
        }
    }

    private fun loadProperties(
        environment: ConfigurableEnvironment,
        properties: AwsSsmProperties,
    ) {
        val paths = resolvedPaths(environment, properties)
        if (paths.isEmpty()) {
            throw AwsSsmConfigException("AWS SSM is enabled but skeleton.config.aws.ssm.paths is empty.")
        }

        val client = clientFactory.create(properties)
        val loaded = linkedMapOf<String, Any>()
        paths.forEach { path ->
            client.getParametersByPath(path)
                .mapKeys { (name, _) -> propertyName(path, name) }
                .forEach { (name, value) ->
                    if (name.isNotBlank()) {
                        loaded[name] = value
                    }
                }
        }

        if (loaded.isNotEmpty()) {
            addPropertySource(environment, loaded)
        }
    }

    private fun resolvedPaths(
        environment: ConfigurableEnvironment,
        properties: AwsSsmProperties,
    ): List<String> {
        val profile = environment.activeProfiles.firstOrNull().orEmpty()
        return properties.paths.mapNotNull { path ->
            path.trim()
                .takeIf { it.isNotBlank() }
                ?.replace("{profile}", profile)
                ?.ensureTrailingSlash()
        }
    }

    private fun propertyName(path: String, parameterName: String): String =
        parameterName
            .removePrefix(path.ensureTrailingSlash())
            .trim('/')
            .replace("/", ".")

    private fun addPropertySource(
        environment: ConfigurableEnvironment,
        values: Map<String, Any>,
    ) {
        val source = MapPropertySource(PROPERTY_SOURCE_NAME, values)
        val sources = environment.propertySources
        if (sources.contains(PROPERTY_SOURCE_NAME)) {
            sources.replace(PROPERTY_SOURCE_NAME, source)
            return
        }
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source)
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, source)
        } else {
            sources.addFirst(source)
        }
    }

    private fun failureMessage(
        environment: ConfigurableEnvironment,
        properties: AwsSsmProperties,
        error: Throwable,
    ): String {
        val profile = environment.activeProfiles.firstOrNull() ?: "local"
        val credentialProfile = properties.credentialProfile.ifBlank { "skeleton-$profile" }
        return buildString {
            appendLine("AWS SSM configuration failed.")
            append("Reason: ")
            appendLine(error.message ?: error.javaClass.name)
            appendLine()
            appendLine("If this profile uses SSM, login or provide AWS credentials:")
            append("  aws sso login --profile ")
            appendLine(credentialProfile)
            append("  AWS_PROFILE=")
            append(credentialProfile)
            append(" SPRING_PROFILES_ACTIVE=")
            append(profile)
            appendLine(" ./gradlew :apps:api:bootRun")
        }.trimEnd()
    }

    private fun String.ensureTrailingSlash(): String =
        if (endsWith("/")) this else "$this/"

    companion object {
        const val PROPERTY_SOURCE_NAME = "awsSsmParameterStore"
    }
}
