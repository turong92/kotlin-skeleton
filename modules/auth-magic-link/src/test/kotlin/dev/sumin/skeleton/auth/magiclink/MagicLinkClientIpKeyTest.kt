package dev.sumin.skeleton.auth.magiclink

import dev.sumin.skeleton.magiclinktest.MagicLinkTestApplication
import dev.sumin.skeleton.magiclinktest.MagicLinkTestBeans
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** I4 — 매직 링크 요청의 IP 한도 키는 `limitKey`(IPv6 /64): 한 /64 안의 주소를 돌려 써도 한도가 하나다 */
@SpringBootTest(
    classes = [MagicLinkTestApplication::class],
    properties = [
        "skeleton.web.client-ip.mode=direct",
        "skeleton.account.mail.link-base-url=https://app.example.com",
        "skeleton.account.password.bcrypt-strength=4",
        "skeleton.auth-magic-link.per-ip=2",
    ],
)
@AutoConfigureMockMvc
@Import(MagicLinkTestBeans::class)
class MagicLinkClientIpKeyTest {
    @Autowired lateinit var mvc: MockMvc

    private fun request(ip: String) =
        mvc.perform(post("/api/v1/auth/magic-link/request").with { it.remoteAddr = ip; it }.contentType(MediaType.APPLICATION_JSON).content("""{"email":"x@example.com"}"""))

    @Test
    fun `magic-link requests are limited per IPv6 slash-64, not per full address`() {
        request("2001:db8:e:1::1").andExpect(status().isAccepted)
        request("2001:db8:e:1::2").andExpect(status().isAccepted)
        request("2001:db8:e:1::3").andExpect(status().isTooManyRequests)
        request("2001:db8:e:2::1").andExpect(status().isAccepted)
    }
}
