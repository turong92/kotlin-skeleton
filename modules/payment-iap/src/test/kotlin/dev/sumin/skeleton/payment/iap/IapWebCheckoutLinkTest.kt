package dev.sumin.skeleton.payment.iap

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.common.account.WebHandoffIssuer
import dev.sumin.skeleton.iaptest.IapLedgerBeans
import dev.sumin.skeleton.iaptest.IapWebTestApplication
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class RecordingHandoff : WebHandoffIssuer {
    val issuedFor = CopyOnWriteArrayList<String>()
    override fun issue(accountId: String): String = "code-for-$accountId".also { issuedFor += accountId }
}

@TestConfiguration(proxyBeanMethods = false)
class HandoffBeans {
    @Bean fun handoff(): WebHandoffIssuer = RecordingHandoff()
}

/**
 * 앱 -> 웹 결제 넘어가기(스펙 「결정됨」 3). 웹이 들어 있는 채널의 로그인한 앱에게만 1회용 코드를 단 주소를 준다 - 코드는 `#` 조각에 실려 서버 접근 로그에 안 남고,
 * 응답은 캐시되지 않는다. IAP_ONLY 이면 코드를 만들지도 않는다.
 */
@SpringBootTest(
    classes = [IapWebTestApplication::class],
    properties = [
        "skeleton.payment-iap.enabled=true",
        "skeleton.payment-iap.web-checkout-url=https://example.com/pricing",
        "skeleton.payment-iap.channels.countries.US.ios=IAP_AND_WEB",
        "skeleton.payment-iap.products[0].item-type=cheer",
        "skeleton.payment-iap.products[0].item-ref=cheer-3000",
        "skeleton.payment-iap.products[0].ios-product-id=com.example.cheer3000",
        "skeleton.payment-iap.products[0].play-product-id=cheer_3000",
    ],
)
@AutoConfigureMockMvc
@Import(IapLedgerBeans::class, HandoffBeans::class)
class IapWebCheckoutLinkTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var handoff: WebHandoffIssuer

    private val path = "/api/v1/payments/iap/web-checkout-link"
    private fun recorded() = (handoff as RecordingHandoff).issuedFor

    private fun link(account: String?, platform: String, storefront: String?) = mvc.perform(
        post(path).param("platform", platform).let { b -> if (storefront != null) b.param("storefront", storefront) else b }
            .let { b -> if (account != null) b.principal(UsernamePasswordAuthenticationToken(account, "n/a", emptyList())) else b },
    )

    @Test
    fun `a signed-in app in a channel with the web gets the checkout address with a one-time code in the fragment and no caching`() {
        val r = link("acc_link_1", "ios", "USA").andExpect(status().isOk).andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store"))).andReturn()
        assertEquals("https://example.com/pricing#handoff=code-for-acc_link_1", JsonPath.read<String>(r.response.contentAsString, "$.value.url"))
        assertEquals(listOf("acc_link_1"), recorded().filter { it == "acc_link_1" })
    }

    @Test
    fun `an in-app only channel gets no link and no code is made`() {
        val before = recorded().size
        link("acc_link_2", "ios", "KOR").andExpect(status().isNotFound)
        link("acc_link_2", "android", "US").andExpect(status().isNotFound)
        assertEquals(before, recorded().size)
    }

    @Test
    fun `a missing login is 401 and an unknown platform is 400, with no code made`() {
        val before = recorded().size
        link(null, "ios", "USA").andExpect(status().isUnauthorized)
        link("acc_link_3", "windows", "USA").andExpect(status().isBadRequest)
        assertTrue(recorded().size == before)
    }
}
