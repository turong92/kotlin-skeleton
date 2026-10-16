package dev.sumin.skeleton.crypto

import dev.sumin.skeleton.common.project.NoProjectContextException
import dev.sumin.skeleton.common.project.ProjectAutoConfiguration
import dev.sumin.skeleton.common.project.ProjectContext
import dev.sumin.skeleton.common.project.ProjectRegistry
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/** 프로젝트 모드의 암호화: 키는 프로젝트마다, 한 프로젝트의 암호문은 다른 프로젝트 키로 풀리지 않는다 */
class CryptoProjectsTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, CryptoAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts=a.test", "skeleton.projects.a.schema=pa", "skeleton.projects.b.hosts=b.test", "skeleton.projects.b.schema=pb")

    private val a = "skeleton.projects.a.config.crypto"
    private val b = "skeleton.projects.b.config.crypto"

    private fun keyed(aSeed: Int, bSeed: Int) = runner.withPropertyValues("$a.keys.local=${key(aSeed)}", "$b.keys.local=${key(bSeed)}")

    @Test
    fun `a ciphertext made in project A cannot be opened in project B even with the same key id`() {
        keyed(1, 2).run { ctx ->
            val encryptor = ctx.getBean(TextEncryptor::class.java)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            val cipher = ProjectContext.runIn(pa) { encryptor.encrypt("billing-key") }
            assertEquals("billing-key", ProjectContext.runIn(pa) { encryptor.decrypt(cipher) })
            assertFailsWith<CryptoException> { ProjectContext.runIn(pb) { encryptor.decrypt(cipher) } }
        }
    }

    @Test
    fun `an opaque url token made in project A is refused in project B`() {
        keyed(1, 2).run { ctx ->
            val codec = ctx.getBean(OpaqueUrlTokenCodec::class.java)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            val token = ProjectContext.runIn(pa) { codec.encode(OpaqueUrlTokenPayload(value = "k", purpose = "p")) }
            assertEquals("k", ProjectContext.runIn(pa) { codec.decode(token).value })
            assertFailsWith<CryptoException> { ProjectContext.runIn(pb) { codec.decode(token) } }
        }
    }

    @Test
    fun `two projects with the same key bytes stop the start and the message names both`() {
        keyed(1, 1).run { ctx ->
            val messages = generateSequence<Throwable>(ctx.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
            assertTrue(messages.any { "'a'" in it && "'b'" in it && "same key" in it }, messages.toString())
        }
    }

    @Test
    fun `a closed project does not count for the same-key check`() {
        keyed(1, 1).withPropertyValues("skeleton.projects.b.status=closed", "skeleton.projects.b.schema=").run { ctx ->
            assertNotNull(ctx.getBean(TextEncryptor::class.java))
        }
    }

    @Test
    fun `without a project in context the encryptor refuses, and a project without keys names itself`() {
        runner.withPropertyValues("$a.keys.local=${key(1)}").run { ctx ->
            val encryptor = ctx.getBean(TextEncryptor::class.java)
            val (pa, pb) = ctx.getBean(ProjectRegistry::class.java).projects
            assertFailsWith<NoProjectContextException> { encryptor.encrypt("x") }
            assertEquals("x", ProjectContext.runIn(pa) { encryptor.decrypt(encryptor.encrypt("x")) })
            val ex = assertFailsWith<CryptoException> { ProjectContext.runIn(pb) { encryptor.encrypt("x") } }
            assertTrue("project 'b'" in ex.message.orEmpty(), ex.message)
        }
    }

    @Test
    fun `no keys anywhere keeps the module passive in project mode too`() {
        runner.run { ctx -> assertTrue(ctx.getBeansOfType(TextEncryptor::class.java).isEmpty()) }
    }

    @Test
    fun `a TextEncryptor the app defined is shared by all projects - warning, and a start failure in a protected environment`() {
        val custom = object : TextEncryptor {
            override fun encrypt(plainText: String) = plainText
            override fun decrypt(cipherText: String) = cipherText
        }
        val prod = dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.PROD, emptySet())
        val local = dev.sumin.skeleton.common.deploy.DeployContext(dev.sumin.skeleton.common.deploy.DeployEnv.LOCAL, emptySet())
        runner.withBean(TextEncryptor::class.java, java.util.function.Supplier { custom }).run { ctx ->
            val guard = ctx.getBean("cryptoSharedEncryptorDeployGuard", dev.sumin.skeleton.common.deploy.DeployGuard::class.java)
            assertEquals(1, guard.problems(prod).size)
            assertEquals(1, guard.warnings(local).size)
        }
        keyed(1, 2).run { ctx ->
            val guard = ctx.getBean("cryptoSharedEncryptorDeployGuard", dev.sumin.skeleton.common.deploy.DeployGuard::class.java)
            assertEquals(emptyList(), guard.problems(prod), "the module's own routed encryptor is fine")
        }
    }

    @Test
    fun `keys written only for a closed project do not switch the module on`() {
        runner.withPropertyValues("$b.keys.local=${key(2)}", "skeleton.projects.b.status=closed", "skeleton.projects.b.schema=").run { ctx ->
            assertTrue(ctx.getBeansOfType(TextEncryptor::class.java).isEmpty())
        }
    }

    private fun key(seed: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { (seed + it).toByte() })
}
