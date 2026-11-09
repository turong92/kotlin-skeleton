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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 구글이 부르는 SSV 콜백 — 시험용 EC 키 쌍으로 서명한다(구글의 실제 키에 기대지 않는다) */
@SpringBootTest(
    classes = [AdsTestApplication::class],
    properties = ["skeleton.ads-admob.enabled=true", "skeleton.ads-admob.ad-units=ca-app-pub-1111/2222"],
)
@AutoConfigureMockMvc
@Import(AdsFakes::class)
class AdmobSsvWebTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var wallet: RecordingWallet
    @Autowired lateinit var source: FakeKeySource
    @Autowired lateinit var time: MutableTime

    private companion object {
        // 컨텍스트(와 그 안의 키 캐시)가 시험마다 새로 서지 않으므로 키도 한 벌로 쓴다
        val googleKey = newEcKeyPair()
    }

    @BeforeTest
    fun reset() {
        wallet.entries.clear()
        wallet.failing = false
        source.failing = false
        source.keys = mapOf("1" to googleKey.public)
        time.now = time.now.plus(Duration.ofMinutes(5))   // 모르는 키 다시 읽기 간격을 지난 상태로 시작
    }

    private fun call(query: String) = mvc.perform(get(java.net.URI.create("/api/v1/ads/admob/ssv?$query")))

    @Test
    fun `a callback signed by the google key earns the signed reward amount`() {
        call(signedQuery(callback(), "1", googleKey)).andExpect(status().isOk)

        val entry = wallet.entries.single()
        assertEquals("acc_1", entry.accountId)
        assertEquals(10L, entry.delta)
        assertEquals("ADMOB_SSV", entry.sourceType)
        assertEquals("tx-1", entry.sourceRef)
        assertNull(entry.expiresAt)
    }

    @Test
    fun `google resending the same transaction id gets 200 again and earns once`() {
        val query = signedQuery(callback(), "1", googleKey)

        call(query).andExpect(status().isOk)
        call(query).andExpect(status().isOk)

        assertEquals(1, wallet.entries.size)
    }

    @Test
    fun `one changed character in the signed part is 400 and earns nothing`() {
        val query = signedQuery(callback(amount = "10"), "1", googleKey).replace("reward_amount=10", "reward_amount=99")

        call(query).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `a signature made with another key is 400`() {
        call(signedQuery(callback(), "1", newEcKeyPair())).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `a validly signed callback for an ad unit that is not on the list is 400`() {
        call(signedQuery(callback(unit = "ca-app-pub-9999/8888"), "1", googleKey)).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `the callback carries only the number after the slash and still matches the console id in the list`() {
        call(signedQuery(callback(unit = "2222"), "1", googleKey)).andExpect(status().isOk)

        assertEquals(1, wallet.entries.size)
    }

    @Test
    fun `a user_id longer than 128 characters is 400`() {
        call(signedQuery(callback(user = "a".repeat(129)), "1", googleKey)).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `a validly signed callback without user_id is 400`() {
        call(signedQuery(callback(user = null), "1", googleKey)).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `a request without signature or key_id is 400`() {
        call("ad_unit=ca-app-pub-1111/2222&user_id=acc_1&transaction_id=tx-1&reward_amount=10").andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/ads/admob/ssv")).andExpect(status().isBadRequest)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `an unknown key_id re-reads the key file once and then is 400`() {
        val before = source.loads.get()

        call(signedQuery(callback(), "404", googleKey)).andExpect(status().isBadRequest)

        // 처음 읽기가 이미 있었든 없었든, 이 한 번의 요청은 많아야 두 번(처음 읽기 + 다시 읽기)
        assertEquals(true, source.loads.get() - before <= 2)
        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `when the key file cannot be fetched the answer is 503 so google retries`() {
        source.failing = true

        call(signedQuery(callback(), "404", googleKey)).andExpect(status().isServiceUnavailable)

        assertEquals(0, wallet.entries.size)
    }

    @Test
    fun `a ledger failure is 503 so google retries`() {
        wallet.failing = true

        call(signedQuery(callback(), "1", googleKey)).andExpect(status().isServiceUnavailable)
    }
}
