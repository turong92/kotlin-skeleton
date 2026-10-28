package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.FakeCatalogStore
import dev.sumin.skeleton.catalogtest.CatalogTestBeans
import dev.sumin.skeleton.catalogtest.CatalogWebTestApplication
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** 운영자 경로: 권한(익명 401 · 없음 403), C-CADM 본문 모양, 오류 코드, 감사 기록. */
@SpringBootTest(classes = [CatalogWebTestApplication::class], properties = ["skeleton.catalog.admin.enabled=true"])
@AutoConfigureMockMvc
@Import(CatalogTestBeans::class)
class CatalogAdminControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var store: FakeCatalogStore

    private val operator = TestingAuthenticationToken("acc_op", "n/a", "ROLE_USER")
    private val other = TestingAuthenticationToken("acc_other", "n/a", "ROLE_USER")
    private val json = MediaType.APPLICATION_JSON
    private val base = "/api/v1/admin/catalog"

    @BeforeEach
    fun reset() {
        store.products.clear()
        store.prices.clear()
        store.refs.clear()
        CatalogTestBeans.GRANTS.clear()
        CatalogTestBeans.GRANTS["acc_op"] = setOf("catalog.manage")
        CatalogTestBeans.AUDIT.clear()
    }

    private fun send(builder: org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder, body: String? = null): ResultActions =
        mvc.perform(builder.principal(operator).contentType(json).apply { if (body != null) content(body) })

    private fun product(code: String = "basic", level: Int = 1) =
        send(post("$base/products"), """{"code":"$code","kind":"SUBSCRIPTION","level":$level,"highlighted":true,"sortOrder":2,"texts":{"en":{"name":"Basic","description":"The basics"},"ko":{"name":"베이직"}}}""")

    private fun price(body: String, product: String = "basic") = send(post("$base/products/$product/prices"), body)

    @Test
    fun `an anonymous caller is 401 and a caller without catalog manage 403 on every route`() {
        listOf(get("$base/products"), get("$base/providers"), post("$base/products"), put("$base/products/basic"), post("$base/products/basic/prices"), post("$base/prices/basic/stop"), patch("$base/prices/basic"), put("$base/prices/basic/provider-refs/paddle"), delete("$base/prices/basic/provider-refs/paddle")).forEach { request ->
            mvc.perform(request.contentType(json).content("{}")).andExpect(status().isUnauthorized)
            mvc.perform(request.principal(other).contentType(json).content("{}")).andExpect(status().isForbidden)
        }
    }

    @Test
    fun `a product is created with the shape the screens read, listed with its prices newest first, and the same code again is 409`() {
        product().andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.code").value("basic"))
            .andExpect(jsonPath("$.value.kind").value("SUBSCRIPTION"))
            .andExpect(jsonPath("$.value.level").value(1))
            .andExpect(jsonPath("$.value.highlighted").value(true))
            .andExpect(jsonPath("$.value.selling").value(true))
            .andExpect(jsonPath("$.value.sortOrder").value(2))
            .andExpect(jsonPath("$.value.texts.ko.name").value("베이직"))
            .andExpect(jsonPath("$.value.texts.en.description").value("The basics"))
            .andExpect(jsonPath("$.value.prices.length()").value(0))
        price("""{"code":"basic","period":"P1M","amount":3900,"currency":"KRW"}""").andExpect(status().isCreated)
        price("""{"code":"basic-usd","period":"P1M","amount":300,"currency":"USD"}""").andExpect(status().isCreated)

        send(get("$base/products")).andExpect(status().isOk)
            .andExpect(jsonPath("$.values.length()").value(1))
            .andExpect(jsonPath("$.values[0].prices.length()").value(2))
            .andExpect(jsonPath("$.values[0].prices[0].code").value("basic-usd"))
            .andExpect(jsonPath("$.values[0].prices[1].code").value("basic"))
            .andExpect(jsonPath("$.values[0].prices[1].amount").value(3_900))
            .andExpect(jsonPath("$.values[0].prices[1].current").value(true))
            .andExpect(jsonPath("$.values[0].prices[1].providerRefs").isEmpty)

        product().andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CATALOG.CODE_TAKEN"))
        product("standard", level = 1).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CATALOG.LEVEL_TAKEN"))
        product("Bad Code", level = 9).andExpect(status().isBadRequest)
    }

    @Test
    fun `price errors carry their catalog codes - overlap 409, invalid 400, missing 404`() {
        product()
        price("""{"code":"basic","period":"P1M","amount":3900,"currency":"KRW"}""").andExpect(status().isCreated)
            .andExpect(jsonPath("$.value.code").value("basic")).andExpect(jsonPath("$.value.current").value(true)).andExpect(jsonPath("$.value.selling").value(true))
        price("""{"period":"P1M","amount":4900,"currency":"KRW"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CATALOG.PRICE_OVERLAP"))
        price("""{"period":"P1M","amount":0,"currency":"KRW"}""").andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("CATALOG.PRICE_INVALID"))
        price("""{"period":"P1M","amount":100,"currency":"USD"}""", "missing").andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CATALOG.NOT_FOUND"))
        send(post("$base/prices/missing/stop")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("CATALOG.NOT_FOUND"))
    }

    @Test
    fun `stopping a price shows it stopped and no longer current, and the end of a sale can be moved`() {
        product()
        val tomorrow = Instant.now().plusSeconds(86_400)
        price("""{"code":"basic","period":"P1M","amount":3900,"currency":"KRW"}""")
        price("""{"code":"basic-sale","period":"P1M","amount":2900,"currency":"KRW","compareAtAmount":3900,"saleStartsAt":"${Instant.now().minusSeconds(60)}","saleEndsAt":"$tomorrow"}""")
            .andExpect(status().isCreated).andExpect(jsonPath("$.value.compareAtAmount").value(3_900)).andExpect(jsonPath("$.value.current").value(true))

        send(patch("$base/prices/basic-sale"), """{"saleEndsAt":"${tomorrow.plusSeconds(3_600)}"}""").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.saleEndsAt").value(tomorrow.plusSeconds(3_600).toString()))
        send(patch("$base/prices/basic-sale"), """{}""").andExpect(status().isBadRequest)
        send(patch("$base/prices/basic-sale"), """{"saleEndsAt":"tomorrow"}""").andExpect(status().isBadRequest)

        send(post("$base/prices/basic-sale/stop")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.selling").value(false)).andExpect(jsonPath("$.value.current").value(false)).andExpect(jsonPath("$.value.stoppedAt").isNotEmpty)
        send(get("$base/products")).andExpect(jsonPath("$.values[0].prices[?(@.code=='basic')].current").value(true))
    }

    @Test
    fun `provider links can be set and removed, the provider list shows the providers that use price ids, and a taken id is 409`() {
        product()
        price("""{"code":"basic-usd","period":"P1M","amount":300,"currency":"USD"}""")
        price("""{"code":"basic-yearly-usd","period":"P1Y","amount":3000,"currency":"USD"}""")

        send(put("$base/prices/basic-usd/provider-refs/paddle"), """{"externalId":"pri_1"}""").andExpect(status().isOk).andExpect(jsonPath("$.value.providerRefs.paddle").value("pri_1"))
        send(put("$base/prices/basic-yearly-usd/provider-refs/paddle"), """{"externalId":"pri_1"}""").andExpect(status().isConflict).andExpect(jsonPath("$.code").value("CATALOG.CODE_TAKEN"))
        send(put("$base/prices/basic-usd/provider-refs/paddle"), """{}""").andExpect(status().isBadRequest)
        send(get("$base/providers")).andExpect(status().isOk).andExpect(jsonPath("$.values[0]").value("paddle"))
        send(delete("$base/prices/basic-usd/provider-refs/paddle")).andExpect(status().isOk).andExpect(jsonPath("$.value.providerRefs").isEmpty)
    }

    @Test
    fun `a product can be edited and every write is in the audit log with the operator`() {
        product()
        send(put("$base/products/basic"), """{"selling":false,"highlighted":false,"texts":{"en":{"name":"Basic plan"}}}""").andExpect(status().isOk)
            .andExpect(jsonPath("$.value.selling").value(false)).andExpect(jsonPath("$.value.texts.en.name").value("Basic plan")).andExpect(jsonPath("$.value.texts.ko").doesNotExist())
        send(put("$base/products/missing"), """{}""").andExpect(status().isNotFound)

        assertEquals(listOf("catalog.product.created", "catalog.product.updated"), CatalogTestBeans.AUDIT.map { it.action })
        assertEquals(setOf("acc_op"), CatalogTestBeans.AUDIT.map { it.actorId }.toSet())
    }
}
