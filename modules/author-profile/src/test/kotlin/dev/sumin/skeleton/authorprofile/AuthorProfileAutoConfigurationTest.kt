package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountAuthorDirectory
import dev.sumin.skeleton.account.AccountAutoConfiguration
import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.common.author.AuthorAdoptionListener
import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities
import dev.sumin.skeleton.common.erasure.AccountErasureListener
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class AuthorProfileAutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    class FakeRepository {
        @Bean fun authorProfileRepository(): AuthorProfileRepository = FakeAuthorProfileRepository()
    }

    private val base = listOf("skeleton.account.password.bcrypt-strength=4")

    /** 나열 순서는 [AutoConfigurations] 가 어노테이션(before/after)으로 다시 정렬한다 — 두 순서 모두 같아야 한다 */
    private fun runner(accountFirst: Boolean = true) = ApplicationContextRunner()
        .withConfiguration(
            if (accountFirst) AutoConfigurations.of(AccountAutoConfiguration::class.java, AuthorProfileAutoConfiguration::class.java)
            else AutoConfigurations.of(AuthorProfileAutoConfiguration::class.java, AccountAutoConfiguration::class.java),
        )
        .withPropertyValues(*base.toTypedArray())

    @Test
    fun `with a repository the module registers identities, directory, erasure hook, service and adoption runner`() {
        runner().withUserConfiguration(FakeRepository::class.java).run { ctx ->
            assertNull(ctx.startupFailure)
            assertTrue(ctx.getBean(AuthorIdentities::class.java) is AuthorProfileIdentities)
            assertTrue(ctx.getBean(AuthorDirectory::class.java) is AuthorProfileDirectory)
            assertNotNull(ctx.getBean(AuthorProfileService::class.java))
            assertEquals(1, ctx.getBeansOfType(AuthorProfileProperties::class.java).size)
            assertTrue(ctx.getBeansOfType(AccountErasureListener::class.java).values.any { it is AuthorProfileErasureListener })
            assertNotNull(ctx.getBean("authorProfileAdoptionRunner"))
        }
    }

    @Test
    fun `the author directory is ours whichever order the auto-configurations are listed in - it is registered before the account module's`() {
        for (accountFirst in listOf(true, false)) {
            runner(accountFirst).withUserConfiguration(FakeRepository::class.java).run { ctx ->
                assertEquals(1, ctx.getBeansOfType(AuthorDirectory::class.java).size, "accountFirst=$accountFirst")
                assertTrue(ctx.getBean(AuthorDirectory::class.java) is AuthorProfileDirectory, "accountFirst=$accountFirst")
            }
        }
    }

    @Test
    fun `without this module the account module's directory stays`() {
        ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AccountAutoConfiguration::class.java)).withPropertyValues(*base.toTypedArray()).run { ctx ->
            assertTrue(ctx.getBean(AuthorDirectory::class.java) is AccountAuthorDirectory)
        }
    }

    @Test
    fun `ids that are not profile ids still resolve through the account directory`() {
        runner().withUserConfiguration(FakeRepository::class.java).run { ctx ->
            val accounts = ctx.getBean(AccountRepository::class.java)
            val account = dev.sumin.skeleton.account.Account(
                "acc_1", "a@example.com", true, dev.sumin.skeleton.account.AccountStatus.ACTIVE, setOf("USER"), "Old Name", null, null, T0, T0,
                displayNameKey = "old name", displayTag = "0007",
            )
            assertTrue(accounts.insert(account, emptyList()))
            val cards = ctx.getBean(AuthorDirectory::class.java).resolve(listOf("acc_1"), AuthorContext("board"))
            assertEquals(AuthorCard("Old Name", "0007"), cards["acc_1"])
        }
    }

    @Test
    fun `without a repository the context fails and names the missing port`() {
        runner().run { ctx ->
            assertNotNull(ctx.startupFailure)
            assertTrue(generateSequence(ctx.startupFailure) { it.cause }.any { it.message.orEmpty().contains("AuthorProfileRepository") }, ctx.startupFailure.toString())
        }
    }

    @Test
    fun `app beans of the same types replace ours`() {
        val identities = AuthorIdentities { _, _, _ -> true }
        val directory = AuthorDirectory { ids, _ -> ids.associateWith { AuthorCard("mine") } }
        runner().withUserConfiguration(FakeRepository::class.java)
            .withBean(AuthorIdentities::class.java, Supplier { identities })
            .withBean(AuthorDirectory::class.java, Supplier { directory })
            .run { ctx ->
                assertTrue(ctx.getBean(AuthorIdentities::class.java) === identities)
                assertTrue(ctx.getBean(AuthorDirectory::class.java) === directory)
                assertEquals(1, ctx.getBeansOfType(AuthorDirectory::class.java).size)
            }
    }

    @Test
    fun `the adoption runner moves old rows at startup and stays quiet when switched off`() {
        class Listener : AuthorAdoptionListener {
            override val name = "board"
            var left = 2
            override fun pendingAccounts(limit: Int): List<String> = if (left > 0) listOf("acc_1") else emptyList()
            override fun adopt(accountId: String, authorId: String): Int = left.also { left = 0 }
        }
        val on = Listener()
        runner().withUserConfiguration(FakeRepository::class.java).withBean(AuthorAdoptionListener::class.java, Supplier { on }).run { ctx ->
            seedAccount(ctx.getBean(AccountRepository::class.java), "acc_1")
            ctx.getBean("authorProfileAdoptionRunner", ApplicationRunner::class.java).run(DefaultApplicationArguments())
            assertEquals(0, on.left)
            assertNotNull(ctx.getBean(AuthorProfileRepository::class.java).findPrimary("acc_1"))
        }
        val off = Listener()
        runner().withUserConfiguration(FakeRepository::class.java).withPropertyValues("skeleton.author-profile.adopt-on-startup=false")
            .withBean(AuthorAdoptionListener::class.java, Supplier { off }).run { ctx ->
                ctx.getBean("authorProfileAdoptionRunner", ApplicationRunner::class.java).run(DefaultApplicationArguments())
                assertEquals(2, off.left)
                assertFalse(ctx.getBean(AuthorProfileRepository::class.java).findPrimary("acc_1") != null)
            }
    }

    private fun seedAccount(accounts: AccountRepository, id: String) {
        accounts.insert(
            dev.sumin.skeleton.account.Account(id, "$id@example.com", true, dev.sumin.skeleton.account.AccountStatus.ACTIVE, setOf("USER"), null, null, null, T0, T0),
            emptyList(),
        )
    }
}
