package dev.sumin.skeleton.common.config

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequiredConfigValidatorTest {
    @Test
    fun `local ignores dev staging prod requirements`() {
        RequiredConfigValidator.validate(
            activeProfiles = setOf("local"),
            properties = properties(
                requirement(
                    property = "skeleton.auth.jwt.secret",
                    env = "JWT_SECRET",
                    ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                    profiles = setOf("dev", "staging", "prod"),
                    secret = true,
                ),
            ),
            propertyResolver = MapPropertyResolver(emptyMap()),
        )
    }

    @Test
    fun `dev reports missing required value with env and ssm hints`() {
        val error = assertFailsWith<ConfigValidationException> {
            RequiredConfigValidator.validate(
                activeProfiles = setOf("dev"),
                properties = properties(
                    requirement(
                        property = "skeleton.auth.jwt.secret",
                        env = "JWT_SECRET",
                        ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                        profiles = setOf("dev", "staging", "prod"),
                        secret = true,
                    ),
                ),
                propertyResolver = MapPropertyResolver(emptyMap()),
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("skeleton.auth.jwt.secret"))
        assertTrue(message.contains("JWT_SECRET"))
        assertTrue(message.contains("/kotlin-skeleton/dev/skeleton.auth.jwt.secret"))
    }

    @Test
    fun `dev passes when required value is supplied`() {
        RequiredConfigValidator.validate(
            activeProfiles = setOf("dev"),
            properties = properties(
                requirement(
                    property = "skeleton.auth.jwt.secret",
                    env = "JWT_SECRET",
                    ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                    profiles = setOf("dev", "staging", "prod"),
                    secret = true,
                ),
            ),
            propertyResolver = MapPropertyResolver(
                mapOf("skeleton.auth.jwt.secret" to "dev-test-secret-32-bytes-change"),
            ),
        )
    }

    @Test
    fun `prod rejects dummy secret`() {
        val error = assertFailsWith<ConfigValidationException> {
            RequiredConfigValidator.validate(
                activeProfiles = setOf("prod"),
                properties = properties(
                    requirement(
                        property = "skeleton.auth.jwt.secret",
                        env = "JWT_SECRET",
                        ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                        profiles = setOf("dev", "staging", "prod"),
                        secret = true,
                    ),
                ),
                propertyResolver = MapPropertyResolver(
                    mapOf("skeleton.auth.jwt.secret" to "dev-local-jwt-secret-change-me-32-bytes"),
                ),
            )
        }

        val message = error.message.orEmpty()
        assertTrue(message.contains("dummy"))
        assertTrue(message.contains("skeleton.auth.jwt.secret"))
    }

    @Test
    fun `local can allow dummy secret explicitly`() {
        RequiredConfigValidator.validate(
            activeProfiles = setOf("local"),
            properties = properties(
                requirement(
                    property = "skeleton.auth.jwt.secret",
                    env = "JWT_SECRET",
                    ssm = "/kotlin-skeleton/{profile}/skeleton.auth.jwt.secret",
                    profiles = setOf("local"),
                    allowDummyProfiles = setOf("local"),
                    secret = true,
                ),
            ),
            propertyResolver = MapPropertyResolver(
                mapOf("skeleton.auth.jwt.secret" to "dev-local-jwt-secret-change-me-32-bytes"),
            ),
        )
    }

    private fun properties(vararg requirements: ConfigValidationProperties.Requirement): ConfigValidationProperties =
        ConfigValidationProperties(requirements = requirements.toList())

    private fun requirement(
        property: String,
        env: String,
        ssm: String,
        profiles: Set<String>,
        allowDummyProfiles: Set<String> = emptySet(),
        secret: Boolean,
    ): ConfigValidationProperties.Requirement =
        ConfigValidationProperties.Requirement(
            property = property,
            env = env,
            ssm = ssm,
            profiles = profiles,
            allowDummyProfiles = allowDummyProfiles,
            secret = secret,
        )
}
