package dev.sumin.skeleton.ads.webrewarded.jdbc

import com.zaxxer.hikari.HikariDataSource
import dev.sumin.skeleton.ads.webrewarded.WebRewardScope
import dev.sumin.skeleton.ads.webrewarded.WebRewardService
import dev.sumin.skeleton.ads.webrewarded.WebRewardedErrorCode
import dev.sumin.skeleton.ads.webrewarded.WebRewardedException
import dev.sumin.skeleton.ads.webrewarded.WebRewardedProperties
import dev.sumin.skeleton.common.reward.IssuedReward
import dev.sumin.skeleton.common.reward.RewardGrantView
import dev.sumin.skeleton.common.reward.RewardIssuer
import dev.sumin.skeleton.common.reward.RewardLabel
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.flywaydb.core.Flyway
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate

private object WebRewardDb {
    val dataSource = DbTestDatabase.dataSource().let { source ->
        source as DriverManagerDataSource
        HikariDataSource().apply { jdbcUrl = source.url; username = source.username; password = source.password; maximumPoolSize = 16 }
    }.also { Flyway.configure().dataSource(it).locations("classpath:db/migration/${DbTestDatabase.vendor}").load().migrate() }
    val jdbc = NamedParameterJdbcTemplate(dataSource)
    val tx = TransactionTemplate(DataSourceTransactionManager(dataSource))
    var now: Instant = Instant.parse("2026-11-18T03:00:00.123456Z")
    val time = TimeProvider { now }
    val store = JdbcWebRewardStore(jdbc, tx, DbTestDatabase.dialect)
}

private class CountingIssuer : RewardIssuer {
    override val type = "point"
    val issued = CopyOnWriteArrayList<RewardGrantView>()
    @Volatile var failure: RuntimeException? = null

    override fun validate(params: Map<String, String>) = Unit

    override fun issue(grant: RewardGrantView): IssuedReward {
        failure?.let { throw it }
        issued += grant
        return IssuedReward("pt_${issued.size}")
    }

    override fun describe(params: Map<String, String>, locale: Locale) = RewardLabel("t", "m")
}

/** 돈이 걸린 경로를 두 방언의 실 DB 로: 같은 세션 동시 보상 · 상한 직전 동시 보상 · 지급기 실패 되돌림 · 토큰 1회 · 계정 삭제 */
class WebRewardStoreDbTest {
    private val store = WebRewardDb.store
    private val jdbc = WebRewardDb.jdbc
    private val issuer = CountingIssuer()
    private val day = Instant.parse("2026-11-18T00:00:00Z")

    @BeforeTest
    fun clean() {
        WebRewardDb.now = Instant.parse("2026-11-18T03:00:00.123456Z")
        jdbc.update("delete from ads_web_rewarded_sessions", emptyMap<String, Any>())
        jdbc.update("delete from ads_web_rewarded_daily_counts", emptyMap<String, Any>())
    }

    private fun service(properties: WebRewardedProperties = WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"))) =
        WebRewardService(store, properties, issuer, WebRewardDb.time)

    private fun later() {
        WebRewardDb.now = WebRewardDb.now.plus(Duration.ofSeconds(8))
    }

    private fun <T> race(n: Int, task: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        val ready = CountDownLatch(n)
        val go = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit<Result<T>> { ready.countDown(); go.await(); runCatching { task(i) } } }
            ready.await(10, TimeUnit.SECONDS)
            go.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun code(r: Result<*>) = (r.exceptionOrNull() as? WebRewardedException)?.errorCode

    @Test
    fun `concurrent grants of one session pay once and the rest are SESSION_USED`() {
        val s = service()
        val session = s.open("a1", "unlock", "ip1")
        later()

        val results = race(8) { s.grant("a1", session.id) }

        assertEquals(1, results.count { it.isSuccess }, results.toString())
        assertEquals(7, results.count { code(it) == WebRewardedErrorCode.SESSION_USED })
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `two sessions granted at once right at the account limit - only one wins and the counter is exactly the limit`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 1))
        val one = s.open("a1", "unlock", "ip1")
        val two = s.open("a1", "unlock", "ip1")
        later()

        val results = race(2) { i -> s.grant("a1", if (i == 0) one.id else two.id) }

        assertEquals(1, results.count { it.isSuccess }, results.toString())
        assertEquals(1, results.count { code(it) == WebRewardedErrorCode.DAILY_LIMIT })
        assertEquals(1, store.grantedOn(WebRewardScope.ACCOUNT, "a1", day))
        assertEquals(1, issuer.issued.size)
    }

    @Test
    fun `an issuer failure rolls back the session and the counter - then the same session pays`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 1, ipDailyLimit = 1))
        val session = s.open("a1", "unlock", "ip1")
        later()
        issuer.failure = IllegalStateException("point store went away")

        assertEquals(WebRewardedErrorCode.REWARD_UNAVAILABLE, code(runCatching { s.grant("a1", session.id) }))
        assertEquals(0, store.grantedOn(WebRewardScope.ACCOUNT, "a1", day))
        assertEquals(0, store.grantedOn(WebRewardScope.IP, "ip1", day))
        assertNull(jdbc.queryForObject("select granted_at from ads_web_rewarded_sessions where id = :id", mapOf("id" to session.id), java.sql.Timestamp::class.java))

        issuer.failure = null
        assertNotNull(s.grant("a1", session.id).session.grantedAt)
        assertEquals(1, store.grantedOn(WebRewardScope.ACCOUNT, "a1", day))
    }

    @Test
    fun `a token redeems only for its own account and purpose, once, and not after it expires`() {
        val s = service(WebRewardedProperties())
        val session = s.open("a1", "unlock", "ip1")
        later()
        val token = assertNotNull(s.grant("a1", session.id).token)

        assertNull(s.redeem(token, "a2", "unlock"))
        assertNull(s.redeem(token, "a1", "other"))
        assertNotNull(s.redeem(token, "a1", "unlock"))
        assertNull(s.redeem(token, "a1", "unlock"))

        val second = s.open("a1", "unlock", "ip1")
        later()
        val token2 = assertNotNull(s.grant("a1", second.id).token)
        WebRewardDb.now = WebRewardDb.now.plus(Duration.ofMinutes(10))
        assertNull(s.redeem(token2, "a1", "unlock"))
        assertEquals(0, jdbc.queryForObject("select count(*) from ads_web_rewarded_sessions where token_hash = :t", mapOf("t" to token), Long::class.java), "토큰 원문은 저장되지 않는다")
    }

    @Test
    fun `concurrent redeems of one token succeed once`() {
        val s = service(WebRewardedProperties())
        val session = s.open("a1", "unlock", "ip1")
        later()
        val token = assertNotNull(s.grant("a1", session.id).token)

        val results = race(8) { s.redeem(token, "a1", "unlock") }

        assertEquals(1, results.count { it.getOrNull() != null }, results.toString())
    }

    private fun sessionCount() = jdbc.queryForObject("select count(*) from ads_web_rewarded_sessions", emptyMap<String, Any>(), Long::class.java)

    private fun openAt(account: String, at: Instant): String {
        WebRewardDb.now = at
        return service().open(account, "unlock", "ip1").id
    }

    @Test
    fun `purge deletes sessions and counters strictly older than the cutoff - the boundary line stays`() {
        val cutoff = Instant.parse("2026-11-10T00:00:00Z")
        openAt("old", cutoff.minusNanos(1_000))
        openAt("edge", cutoff)
        openAt("new", cutoff.plusSeconds(1))
        store.inTransaction {
            store.incrementWithin(WebRewardScope.ACCOUNT, "a1", Instant.parse("2026-11-09T00:00:00Z"), 5)
            store.incrementWithin(WebRewardScope.IP, "ip1", cutoff, 5)
        }

        val deleted = store.purgeBefore(cutoff, 100)

        assertEquals(2, deleted)
        assertEquals(2, sessionCount())
        assertEquals(0, store.grantedOn(WebRewardScope.ACCOUNT, "a1", Instant.parse("2026-11-09T00:00:00Z")))
        assertEquals(1, store.grantedOn(WebRewardScope.IP, "ip1", cutoff))
        assertEquals(0, store.purgeBefore(cutoff, 100), "멱등 — 다시 돌려도 더 지우지 않는다")
    }

    @Test
    fun `purge keeps an old session whose unused token is still valid, and drops it once the token expired too`() {
        val cutoff = Instant.parse("2026-11-10T00:00:00Z")
        val keep = openAt("a1", cutoff.minusSeconds(3600))
        val drop = openAt("a2", cutoff.minusSeconds(3600))
        store.inTransaction {
            store.markGranted(keep, cutoff.minusSeconds(3000), null, null, "hash-keep", cutoff.plusSeconds(60))
            store.markGranted(drop, cutoff.minusSeconds(3000), null, null, "hash-drop", cutoff.minusSeconds(60))
        }

        store.purgeBefore(cutoff, 100)

        assertEquals(1, sessionCount())
        assertNotNull(store.redeem("hash-keep", "a1", "unlock", cutoff.minusSeconds(100)), "아직 쓸 수 있는 토큰은 지워지지 않았다")
    }

    @Test
    fun `erasing an account removes its sessions and account counters but keeps the ip counters`() {
        val s = service(WebRewardedProperties(rewardType = "point", rewardParams = mapOf("amount" to "1"), accountDailyLimit = 5, ipDailyLimit = 5))
        val session = s.open("a1", "unlock", "ip1")
        s.open("a2", "unlock", "ip2")
        later()
        s.grant("a1", session.id)

        store.inTransaction { store.eraseAccount("a1") }

        assertEquals(1, jdbc.queryForObject("select count(*) from ads_web_rewarded_sessions", emptyMap<String, Any>(), Long::class.java))
        assertEquals(0, store.grantedOn(WebRewardScope.ACCOUNT, "a1", day))
        assertEquals(1, store.grantedOn(WebRewardScope.IP, "ip1", day))
    }
}
