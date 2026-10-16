package dev.sumin.skeleton.payment.web

import dev.sumin.skeleton.common.web.ClientIpMode
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.WebProperties
import dev.sumin.skeleton.payment.PaymentProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull
import org.springframework.mock.web.MockHttpServletRequest

/**
 * 접속 나라 → 결제사 · 해지·환불 정책은 서버가 정한다. 나라를 믿는 길은 Cloudflare 모드의 `CF-IPCountry` 와 로컬 스위치뿐이고,
 * 화면이 보낸 결제사는 서버가 정한 것과 다르면 먹지 않는다.
 */
class PaymentCountryRoutingTest {
    private val cloudflare = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.CLOUDFLARE, trustedProxies = listOf("127.0.0.1/32")))
    private val direct = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT))

    private fun properties(devCountry: String? = null, defaultCountry: String? = "KR", devSwitch: Boolean = false) = PaymentProperties(
        defaultProvider = "paddle",
        routing = PaymentProperties.Routing(countryProviders = mapOf("KR" to "toss"), devCountry = devCountry, devCountrySwitch = devSwitch),
        subscription = PaymentProperties.Subscription(
            cancel = PaymentProperties.Subscription.Cancel(defaultCountry = defaultCountry, policies = mapOf("KR" to "korea"), korea = PaymentProperties.Subscription.Cancel.Korea(penaltyPercent = 10)),
        ),
    )

    private fun routing(ips: ClientIps, properties: PaymentProperties = properties(), openable: List<String> = listOf("paddle", "toss")) =
        PaymentCountryRouting(properties, ips) { openable }

    private fun request(country: String?, dev: String? = null) = MockHttpServletRequest().apply {
        remoteAddr = "127.0.0.1"
        country?.let { addHeader("CF-IPCountry", it) }
        dev?.let { addHeader("X-Dev-Country", it) }
    }

    @Test
    fun `Korea gets Toss and the Korean policy, any other country gets Paddle and the default policy`() {
        val kr = routing(cloudflare).of(request("KR"))
        assertEquals("KR", kr.country)
        assertEquals("toss", kr.provider)
        assertEquals("korea", kr.cancelPolicy.key)
        assertEquals(10, kr.cancelPolicy.penaltyPercent)
        val us = routing(cloudflare).of(request("US"))
        assertEquals("US", us.country)
        assertEquals("paddle", us.provider)
        assertEquals("period-end-only", us.cancelPolicy.key)
        assertNull(us.cancelPolicy.penaltyPercent)
    }

    @Test
    fun `CF-IPCountry is not believed unless the client ip mode is cloudflare - the configured default country decides`() {
        val route = routing(direct).of(request("US"))
        assertEquals("KR", route.country)
        assertEquals("toss", route.provider)
        assertNull(routing(direct, properties(defaultCountry = null)).of(request("US")).country)
    }

    @Test
    fun `the local country switch beats the header, and a request without any country takes the default`() {
        assertEquals("US", routing(direct, properties(devCountry = "US")).of(request("KR")).country)
        assertEquals("KR", routing(cloudflare).of(request(null)).country)
    }

    @Test
    fun `when only one provider is open it is the one - but only for a country the map does not name`() {
        assertEquals("toss", routing(cloudflare, openable = listOf("toss")).of(request("US")).provider)
        assertEquals("paddle", routing(cloudflare, properties(defaultCountry = null).copy(defaultProvider = null), openable = listOf("paddle")).of(request("US")).provider)
    }

    @Test
    fun `a Korean visitor is never sent to Paddle - when Toss cannot open its window the route is closed instead of falling to the only open provider`() {
        val route = routing(cloudflare, openable = listOf("paddle")).of(request("KR"))
        assertNull(route.provider)
        assertTrue(route.providerClosed)
        val abroad = routing(cloudflare, openable = listOf("paddle")).of(request("US"))
        assertEquals("paddle", abroad.provider)
        assertFalse(abroad.providerClosed)
    }

    @Test
    fun `the dev header is ignored completely while the switch is off`() {
        val route = routing(cloudflare).of(request("US", dev = "KR"))
        assertEquals("US", route.country)
        assertFalse(route.devSwitch)
        assertEquals("KR", routing(direct).of(request(null, dev = "US")).country) // default country, not the header
    }

    @Test
    fun `with the switch on the dev header decides the country of that request - a bad value is no override`() {
        val on = routing(cloudflare, properties(devSwitch = true))
        val kr = on.of(request("US", dev = "kr"))
        assertEquals("KR", kr.country)
        assertEquals("toss", kr.provider)
        assertTrue(kr.devSwitch)
        assertEquals("paddle", on.of(request("KR", dev = "JP")).provider)
        assertEquals("US", on.of(request("US", dev = "Korea")).country)
        assertEquals("US", on.of(request("US")).country)
    }

    // --- 같은 나라의 결제사가 둘 이상 열려 있을 때(토스 + 카카오페이) 구매자가 고른다 ---

    private fun choosing(openable: List<String>, countries: Map<String, List<String>>, properties: PaymentProperties = properties()) =
        PaymentCountryRouting({ properties }, cloudflare, { openable }, { country -> countries.filterValues { country in it }.keys.toList() })

    @Test
    fun `a buyer may pick a provider that the operator opened for the buyer's own country - and nothing else`() {
        val routing = choosing(listOf("paddle", "toss", "kakaopay"), mapOf("toss" to listOf("KR"), "kakaopay" to listOf("KR"), "paddle" to listOf("US")))
        val kr = routing.of(request("KR"))

        assertEquals("kakaopay", routing.chosen(kr, " KakaoPay "))
        assertEquals("toss", routing.chosen(kr, "toss"))
        assertEquals("toss", routing.chosen(kr, null), "no choice - the server's own pick")
        assertEquals("toss", routing.chosen(kr, "paddle"), "a provider that does not serve this country is ignored")
        assertEquals("toss", routing.chosen(kr, "nonsense"))
        assertEquals(listOf("toss", "kakaopay"), routing.choices(kr))
        assertEquals("paddle", routing.chosen(routing.of(request("US")), "kakaopay"), "outside Korea the Korean provider is not a choice")
    }

    @Test
    fun `without a mapping for the country two providers are no pick - the buyer must choose, and a closed mapped provider is never replaced by a choice`() {
        val unmapped = choosing(listOf("toss", "kakaopay"), mapOf("toss" to listOf("KR"), "kakaopay" to listOf("KR")), PaymentProperties(subscription = properties().subscription))
        val route = unmapped.of(request("KR"))
        assertNull(route.provider)
        assertEquals("kakaopay", unmapped.chosen(route, "kakaopay"))
        assertNull(unmapped.chosen(route, null))

        val closed = choosing(listOf("kakaopay"), mapOf("kakaopay" to listOf("KR")))
        val closedRoute = closed.of(request("KR"))
        assertTrue(closedRoute.providerClosed)
        assertNull(closed.chosen(closedRoute, "kakaopay"), "the country map names toss and it is closed: no silent fallback, not even by a choice")
    }
}
