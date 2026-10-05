package dev.sumin.skeleton.board.erasure

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.AccountTombstone
import dev.sumin.skeleton.common.erasure.ErasureRequest
import dev.sumin.skeleton.board.BoardAuthors
import dev.sumin.skeleton.board.BoardErasureRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class BoardErasureTest {
    class RecordingRepository : BoardErasureRepository {
        val calls = mutableListOf<Pair<String, String>>()
        override fun anonymizeAuthor(accountId: String, tombstone: String): Int { calls += accountId to tombstone; return 3 }
    }

    @Configuration(proxyBeanMethods = false)
    class Repo { @Bean fun repo(): BoardErasureRepository = RecordingRepository() }

    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BoardErasureAutoConfiguration::class.java))

    @Test
    fun `the board registers an erasure listener when the account module and an erasure repository are there`() {
        runner.withUserConfiguration(Repo::class.java).run { ctx ->
            val listener = ctx.getBean(AccountErasureListener::class.java)
            assertEquals("board", listener.name)
            val tombstone = AccountTombstone.of("acc_1")
            listener.erase(ErasureRequest("acc_1", tombstone))
            assertEquals(listOf("acc_1" to tombstone), (ctx.getBean(BoardErasureRepository::class.java) as RecordingRepository).calls)
        }
    }

    @Test
    fun `without an erasure repository no listener is registered`() {
        runner.run { ctx -> assertTrue(ctx.getBeansOfType(AccountErasureListener::class.java).isEmpty()) }
    }

    @Test
    fun `the deleted-author convention matches the account module's tombstone`() {
        assertTrue(BoardAuthors.isDeleted(AccountTombstone.of("acc_1")))
        assertTrue(!BoardAuthors.isDeleted("acc_1"))
        assertTrue(!BoardAuthors.isDeleted(null))
    }
}
