package dev.sumin.skeleton.notification.jdbc

import dev.sumin.skeleton.common.erasure.ErasureRequest
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class JdbcAdvertisingNoticeStoreDbTest {
    private val dataSource = DbTestDatabase.dataSource().also {
        Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate()
    }
    private val jdbc = NamedParameterJdbcTemplate(dataSource)
    private val store = JdbcAdvertisingNoticeStore(jdbc, DbTestDatabase.dialect)
    private val agreedAt = Instant.parse("2026-10-07T00:00:00.123456Z")

    private fun account() = "acc_${UUID.randomUUID()}"

    @Test
    fun `a mark is per account, type and agreement cycle, and marking twice is one row`() {
        val a = account()
        assertFalse(store.isNoticed(a, "marketing", agreedAt))

        store.markNoticed(a, "marketing", agreedAt, agreedAt.plusSeconds(60))
        store.markNoticed(a, "marketing", agreedAt, agreedAt.plusSeconds(120))   // 같은 주기를 또 표시 - 한 줄, 예외 없음

        assertTrue(store.isNoticed(a, "marketing", agreedAt))
        assertFalse(store.isNoticed(a, "marketing-night", agreedAt))
        assertFalse(store.isNoticed(a, "marketing", agreedAt.plusSeconds(1)), "a reconfirm opens a new cycle")
        assertFalse(store.isNoticed(account(), "marketing", agreedAt))
    }

    @Test
    fun `erasing an account deletes its marks and leaves other accounts' alone`() {
        val gone = account()
        val stay = account()
        store.markNoticed(gone, "marketing", agreedAt, agreedAt)
        store.markNoticed(stay, "marketing", agreedAt, agreedAt)

        NotificationInboxErasureListener(jdbc).erase(ErasureRequest(gone, "deleted:0123456789abcdef"))

        assertFalse(store.isNoticed(gone, "marketing", agreedAt))
        assertTrue(store.isNoticed(stay, "marketing", agreedAt))
        assertEquals(0, jdbc.queryForObject("select count(*) from advertising_notices where account_id = :a", mapOf("a" to gone), Long::class.java))
    }
}
