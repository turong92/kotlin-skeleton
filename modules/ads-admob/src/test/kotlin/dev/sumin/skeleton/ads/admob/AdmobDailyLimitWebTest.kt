package dev.sumin.skeleton.ads.admob

import dev.sumin.skeleton.adstest.AdsFakes
import dev.sumin.skeleton.adstest.AdsTestApplication
import dev.sumin.skeleton.adstest.FakeKeySource
import dev.sumin.skeleton.adstest.MutableTime
import dev.sumin.skeleton.adstest.RecordingWallet
import dev.sumin.skeleton.adstest.callback
import dev.sumin.skeleton.adstest.newEcKeyPair
import dev.sumin.skeleton.adstest.signedQuery
import java.time.Duration
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 하루 상한 — 넘는 콜백은 적립하지 않고 200(구글이 같은 콜백을 다시 보내지 않게) */
@SpringBootTest(
    classes = [AdsTestApplication::class],
    properties = [
        "skeleton.ads-admob.enabled=true", "skeleton.ads-admob.ad-units=ca-app-pub-1111/2222",
        "skeleton.ads-admob.daily-limit=2", "skeleton.ads-admob.day-zone=Asia/Seoul",
    ],
)
@AutoConfigureMockMvc
@Import(AdsFakes::class)
class AdmobDailyLimitWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: RecordingWallet
    @Autowired lateinit var source: FakeKeySource
    @Autowired lateinit var time: MutableTime

    private companion object {
        val googleKey = newEcKeyPair()
    }

    @BeforeTest
    fun reset() {
        wallet.entries.clear()
        source.keys = mapOf("1" to googleKey.public)
        time.now = Instant.parse("2026-11-09T00:00:00Z").plus(Duration.ofMinutes(5))
    }

    private fun ad(tx: String) = mvc.perform(get(java.net.URI.create("/api/v1/ads/admob/ssv?" + signedQuery(callback(tx = tx, amount = "1"), "1", googleKey))))

    @Test
    fun `a callback past the daily limit is 200 and earns nothing`() {
        ad("t1").andExpect(status().isOk)
        ad("t2").andExpect(status().isOk)
        ad("t3").andExpect(status().isOk)

        assertEquals(listOf("t1", "t2"), wallet.entries.map { it.sourceRef })
    }

    @Test
    fun `a resent transaction that was already earned stays 200 at the limit`() {
        ad("t1"); ad("t2")

        ad("t1").andExpect(status().isOk)

        assertEquals(2, wallet.entries.size)
    }

    @Test
    fun `the day is the Seoul calendar day, so it resets at Seoul midnight and not at UTC midnight`() {
        val props = AdmobProperties(dailyLimit = 2, dayZone = "Asia/Seoul")
        val (from, to) = props.dayWindow(Instant.parse("2026-11-09T14:59:59Z"))
        assertEquals(Instant.parse("2026-11-08T15:00:00Z"), from)
        assertEquals(Instant.parse("2026-11-09T15:00:00Z"), to)
        assertEquals(Instant.parse("2026-11-09T15:00:00Z"), props.dayWindow(Instant.parse("2026-11-09T15:00:00Z")).first)
    }

    @Test
    fun `no daily limit configured means no cap`() {
        assertEquals(null, AdmobProperties().dailyLimit)
    }
}
