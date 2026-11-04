package dev.sumin.skeleton.auth.social.apple

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.auth.social.oauth.OAuthProvider
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderRegistry
import dev.sumin.skeleton.auth.social.oauth.OAuthProviderSource
import dev.sumin.skeleton.auth.social.testing.FakeIdp
import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.security.KeyPairGenerator
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.core.env.SystemEnvironmentPropertySource

class AppleAutoConfigurationTest {
    private val pem = pemOf(com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).generate().toECPrivateKey())
    private val http = Supplier<ExternalHttpClient> { FakeIdp.httpClient() }

    private fun on(vararg extra: String) = arrayOf("skeleton.auth-social-apple.client-id=$BUNDLE_ID", "skeleton.auth-social-apple.team-id=$TEAM_ID", "skeleton.auth-social-apple.key-id=$KEY_ID", "skeleton.auth-social-apple.private-key=${pem.replace("\n", "\\n")}", *extra)

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AppleAutoConfiguration::class.java))
        .withBean(ExternalHttpClient::class.java, http)

    private fun startupMessages(ctx: AssertableApplicationContext) = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()

    @Test
    fun `nothing is created while the client id is empty - the default of every project that did not set Sign in with Apple up`() {
        runner.run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
        runner.withPropertyValues("skeleton.auth-social-apple.client-id=", "skeleton.auth-social-apple.team-id=T", "skeleton.auth-social-apple.private-key=k").run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
    }

    @Test
    fun `a client id with the team, key id and key turns the provider on without any other switch`() {
        runner.withPropertyValues(*on()).run { ctx ->
            assertNull(ctx.startupFailure)
            val provider = ctx.getBean(OAuthProvider::class.java)
            assertEquals("apple", provider.providerId)
            assertEquals(BUNDLE_ID, provider.publicClientId)
        }
    }

    @Test
    fun `each missing setting stops the application with a message naming the key and never the key value`() {
        for ((missing, name) in listOf("team-id" to "team-id", "key-id" to "key-id", "private-key" to "private-key")) {
            val values = on().filterNot { it.startsWith("skeleton.auth-social-apple.$missing=") }
            runner.withPropertyValues(*values.toTypedArray()).run { ctx ->
                assertTrue(startupMessages(ctx).any { "skeleton.auth-social-apple.$name" in it }, "$name: ${startupMessages(ctx)}")
            }
        }
    }

    @Test
    fun `a private key that is not an EC PKCS8 PEM stops the application and the message does not repeat it`() {
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
        val rsaPem = "-----BEGIN PRIVATE KEY-----\n" + java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(rsa.encoded) + "\n-----END PRIVATE KEY-----"
        val rsaBody = rsaPem.lines().filterNot { it.startsWith("-----") }
        for (bad in listOf("NOT-A-KEY-SECRETGARBAGE", rsaPem.replace("\n", "\\n"))) {
            val values = on().filterNot { it.startsWith("skeleton.auth-social-apple.private-key=") } + "skeleton.auth-social-apple.private-key=$bad"
            runner.withPropertyValues(*values.toTypedArray()).run { ctx ->
                val messages = startupMessages(ctx)
                assertTrue(messages.any { "skeleton.auth-social-apple.private-key" in it }, messages.toString())
                assertFalse(messages.any { "SECRETGARBAGE" in it || rsaBody.first() in it })
            }
        }
    }

    @Test
    fun `the documented environment names bind as they are - the key may arrive with escaped line breaks`() {
        fun env(vararg e: Pair<String, String>) = runner.withInitializer { ctx -> ctx.environment.propertySources.addFirst(SystemEnvironmentPropertySource("systemEnvironment", mapOf(*e))) }
        env(
            "SKELETON_AUTH_SOCIAL_APPLE_CLIENT_ID" to BUNDLE_ID, "SKELETON_AUTH_SOCIAL_APPLE_TEAM_ID" to TEAM_ID,
            "SKELETON_AUTH_SOCIAL_APPLE_KEY_ID" to KEY_ID, "SKELETON_AUTH_SOCIAL_APPLE_PRIVATE_KEY" to pem.replace("\n", "\\n"),
        ).run { ctx ->
            assertNull(ctx.startupFailure)
            assertEquals(BUNDLE_ID, ctx.getBean(OAuthProvider::class.java).publicClientId)
        }
        env("SKELETON_AUTH_SOCIAL_APPLE_CLIENT_ID" to "", "SKELETON_AUTH_SOCIAL_APPLE_PRIVATE_KEY" to "").run { assertTrue(it.getBeansOfType(OAuthProvider::class.java).isEmpty()) }
    }

    @Test
    fun `the revoke route exists only where Apple is on`() {
        val web = WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AppleAutoConfiguration::class.java, AppleWebAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, http)
            .withBean(OAuthProviderRegistry::class.java, Supplier { OAuthProviderRegistry(emptyList(), AuthSocialProperties()) })
        web.run { assertTrue(it.getBeansOfType(AppleRevokeController::class.java).isEmpty()) }
        web.withPropertyValues(*on()).run { ctx ->
            assertEquals(null, ctx.startupFailure)
            assertEquals(1, ctx.getBeansOfType(AppleRevokeController::class.java).size)
        }
    }

    @Test
    fun `project mode - a project with its own Apple settings has Apple, another one has none, and there is no app-wide provider bean`() {
        val projects = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, AppleAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, http)
        val own = on().map { it.replace("skeleton.auth-social-apple.", "skeleton.projects.a.config.auth-social-apple.") }.toTypedArray()
        projects.withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb", *own).run { ctx ->
            assertNull(ctx.startupFailure)
            assertTrue(ctx.getBeansOfType(OAuthProvider::class.java).isEmpty())
            val registry = ctx.getBean(ProjectRegistry::class.java)
            val source = ctx.getBean(OAuthProviderSource::class.java)
            assertEquals(BUNDLE_ID, source.providersFor(registry.projects.first { it.id == "a" }).single().publicClientId)
            assertTrue(source.providersFor(registry.projects.first { it.id == "b" }).isEmpty())
        }
    }

    @Test
    fun `project mode - a shared bundle id and key do not turn Apple on in a project that did not set its own`() {
        val projects = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, AppleAutoConfiguration::class.java))
            .withBean(ExternalHttpClient::class.java, http)
        projects.withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", *on()).run { ctx ->
            assertNull(ctx.startupFailure)
            val registry = ctx.getBean(ProjectRegistry::class.java)
            assertTrue(ctx.getBean(OAuthProviderSource::class.java).providersFor(registry.projects.first { it.id == "a" }).isEmpty())
        }
    }

    @Test
    fun `the properties print without the key`() {
        assertFalse(AppleProperties(privateKey = "SECRETGARBAGE").toString().contains("SECRETGARBAGE"))
    }
}
