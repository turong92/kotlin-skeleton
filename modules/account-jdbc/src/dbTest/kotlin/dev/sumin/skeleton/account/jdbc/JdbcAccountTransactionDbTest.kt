package dev.sumin.skeleton.account.jdbc

import dev.sumin.skeleton.account.Account
import dev.sumin.skeleton.account.AccountStatus
import dev.sumin.skeleton.account.Identity
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.springframework.jdbc.datasource.DataSourceTransactionManager

/** 가입이 만드는 계정과 같이 가야 하는 쓰기(약관 동의 기록)가 한 트랜잭션이다 — 뒤의 쓰기가 실패하면 계정도 남지 않는다 (두 DB 에서 같게) */
class JdbcAccountTransactionDbTest {
    private val atomic = JdbcAccountTransaction(DataSourceTransactionManager(AccountDb.dataSource))
    private val repo = AccountDb.accounts
    private val now = Instant.parse("2026-10-06T00:00:00.123456Z")

    @BeforeTest fun clean() = AccountDb.clean()

    private fun account() = Account("acc_tx", "tx@example.com", true, AccountStatus.ACTIVE, setOf("USER"), null, null, null, now, now)
    private fun identity() = Identity("idn_tx", "acc_tx", "password", "tx@example.com", true, "{bcrypt}x", null, now)

    @Test
    fun `when the second write fails, the account insert is rolled back with it`() {
        assertFailsWith<IllegalStateException> {
            atomic.run {
                repo.insert(account(), listOf(identity()))
                error("recording the consent failed")
            }
        }
        assertNull(repo.findById("acc_tx"))
    }

    @Test
    fun `when everything succeeds the account is there and the block's value comes back`() {
        val result = atomic.run { repo.insert(account(), listOf(identity())); 42 }
        assertEquals(42, result)
        assertNotNull(repo.findById("acc_tx"))
    }
}
