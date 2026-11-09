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
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 기본 꺼짐 — 켜지 않으면 경로가 없다(404) */
@SpringBootTest(classes = [AdsTestApplication::class])
@AutoConfigureMockMvc
@Import(AdsFakes::class)
class AdmobSwitchOffTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: RecordingWallet

    @Test
    fun `the callback path is 404 until the module is switched on`() {
        mvc.perform(get("/api/v1/ads/admob/ssv?x=1")).andExpect(status().isNotFound)
        assertEquals(0, wallet.entries.size)
    }
}

/** 켰어도 허용 광고 단위가 비어 있으면 모든 콜백을 거절한다 */
@SpringBootTest(classes = [AdsTestApplication::class], properties = ["skeleton.ads-admob.enabled=true"])
@AutoConfigureMockMvc
@Import(AdsFakes::class)
class AdmobEmptyAdUnitsTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: RecordingWallet
    @Autowired lateinit var source: FakeKeySource

    @Test
    fun `with no ad units listed every callback is refused`() {
        val key = newEcKeyPair()
        source.keys = mapOf("1" to key.public)

        mvc.perform(get(java.net.URI.create("/api/v1/ads/admob/ssv?" + signedQuery(callback(), "1", key)))).andExpect(status().isBadRequest)
        assertEquals(0, wallet.entries.size)
    }
}

/** 유효기간 설정 — 기본은 없음(안 사라짐), 일수를 주면 그만큼 뒤에 사라진다 */
@SpringBootTest(
    classes = [AdsTestApplication::class],
    properties = ["skeleton.ads-admob.enabled=true", "skeleton.ads-admob.ad-units=ca-app-pub-1111/2222", "skeleton.ads-admob.expires-in-days=30"],
)
@AutoConfigureMockMvc
@Import(AdsFakes::class)
class AdmobExpiryTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: RecordingWallet
    @Autowired lateinit var source: FakeKeySource
    @Autowired lateinit var time: MutableTime

    @Test
    fun `expires-in-days sets the expiry of the earned bundle`() {
        val key = newEcKeyPair()
        source.keys = mapOf("1" to key.public)
        time.now = time.now.plus(Duration.ofMinutes(5))

        mvc.perform(get(java.net.URI.create("/api/v1/ads/admob/ssv?" + signedQuery(callback(), "1", key)))).andExpect(status().isOk)

        assertEquals(time.now.plus(Duration.ofDays(30)), wallet.entries.single().expiresAt)
    }
}
