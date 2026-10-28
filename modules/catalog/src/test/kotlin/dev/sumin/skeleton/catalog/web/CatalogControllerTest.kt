package dev.sumin.skeleton.catalog.web

import dev.sumin.skeleton.catalog.CatalogAdmin
import dev.sumin.skeleton.catalog.FakeCatalogStore
import dev.sumin.skeleton.catalog.PriceInput
import dev.sumin.skeleton.catalog.ProductInput
import dev.sumin.skeleton.catalog.TEXTS
import dev.sumin.skeleton.catalogtest.CatalogTestBeans
import dev.sumin.skeleton.catalogtest.CatalogWebTestApplication
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import kotlin.test.Test

/** 공개 읽기: 나라로 통화가 갈린다(KR → KRW, 그 밖 → USD), 개인 캐시 60초 + Vary, 꺼짐 404, 잘못된 kind 400. */
@SpringBootTest(classes = [CatalogWebTestApplication::class], properties = ["skeleton.catalog.http.enabled=true"])
@AutoConfigureMockMvc
@Import(CatalogTestBeans::class)
class CatalogControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var admin: CatalogAdmin
    @Autowired lateinit var store: FakeCatalogStore

    @BeforeEach
    fun seed() {
        store.products.clear()
        store.prices.clear()
        admin.createProduct("seed", ProductInput("basic", "SUBSCRIPTION", 1, highlighted = true, texts = TEXTS))
        admin.addPrice("seed", "basic", PriceInput("basic", "P1M", 3_900, "KRW"))
        admin.addPrice("seed", "basic", PriceInput("basic-yearly", "P1Y", 39_000, "KRW"))
        admin.addPrice("seed", "basic", PriceInput("basic-usd", "P1M", 300, "USD"))
        admin.createProduct("seed", ProductInput("sticker", "ONE_TIME", null, sortOrder = 1, texts = TEXTS))
        admin.addPrice("seed", "sticker", PriceInput("sticker-usd", null, 200, "USD"))
    }

    @Test
    fun `a Korean visitor sees the KRW prices and an American one the USD prices, anonymously, with the request currency in the body`() {
        mvc.perform(get("/api/v1/catalog/products").header("CF-IPCountry", "KR")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.currency").value("KRW"))
            .andExpect(jsonPath("$.value.products.length()").value(1))
            .andExpect(jsonPath("$.value.products[0].code").value("basic"))
            .andExpect(jsonPath("$.value.products[0].kind").value("SUBSCRIPTION"))
            .andExpect(jsonPath("$.value.products[0].level").value(1))
            .andExpect(jsonPath("$.value.products[0].highlighted").value(true))
            .andExpect(jsonPath("$.value.products[0].texts.ko.name").value("베이직"))
            .andExpect(jsonPath("$.value.products[0].texts.en.description").value("The basics"))
            .andExpect(jsonPath("$.value.products[0].prices.length()").value(2))
            .andExpect(jsonPath("$.value.products[0].prices[0].code").value("basic"))
            .andExpect(jsonPath("$.value.products[0].prices[0].period").value("P1M"))
            .andExpect(jsonPath("$.value.products[0].prices[0].amount").value(3_900))
            .andExpect(jsonPath("$.value.products[0].prices[0].currency").value("KRW"))
            .andExpect(jsonPath("$.value.products[0].prices[0].compareAtAmount").value(null as Any?))
            .andExpect(jsonPath("$.value.products[0].prices[1].code").value("basic-yearly"))

        mvc.perform(get("/api/v1/catalog/products").header("CF-IPCountry", "US")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.currency").value("USD"))
            .andExpect(jsonPath("$.value.products.length()").value(2))
            .andExpect(jsonPath("$.value.products[0].prices[0].code").value("basic-usd"))
            .andExpect(jsonPath("$.value.products[1].prices[0].amount").value(200))
    }

    @Test
    fun `the answer is a private cache for sixty seconds that varies by the country headers, and by the dev header too when the local switch is on`() {
        mvc.perform(get("/api/v1/catalog/products").header("CF-IPCountry", "KR")).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "max-age=60, private"))
            .andExpect(header().string("Vary", "CF-IPCountry, X-Dev-Country"))
    }

    @Test
    fun `the local switch lets a request look at the other currency, and the browser cannot pick a currency any other way`() {
        mvc.perform(get("/api/v1/catalog/products").header("CF-IPCountry", "KR").header("X-Dev-Country", "US")).andExpect(jsonPath("$.value.currency").value("USD"))
        mvc.perform(get("/api/v1/catalog/products?currency=USD").header("CF-IPCountry", "KR")).andExpect(jsonPath("$.value.currency").value("KRW"))
    }

    @Test
    fun `the kind filter narrows the products and a bad kind is a 400`() {
        mvc.perform(get("/api/v1/catalog/products?kind=ONE_TIME").header("CF-IPCountry", "US")).andExpect(status().isOk)
            .andExpect(jsonPath("$.value.products.length()").value(1)).andExpect(jsonPath("$.value.products[0].code").value("sticker"))
        mvc.perform(get("/api/v1/catalog/products?kind=nonsense")).andExpect(status().isBadRequest)
    }
}

/** 기본은 꺼짐 - 경로가 없어 404 (공개 읽기도 운영자 경로도) */
@SpringBootTest(classes = [CatalogWebTestApplication::class])
@AutoConfigureMockMvc
@Import(CatalogTestBeans::class)
class CatalogDisabledTest {
    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `without the switches the public read and the operator paths are 404`() {
        mvc.perform(get("/api/v1/catalog/products")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/admin/catalog/products")).andExpect(status().isNotFound)
    }
}
