package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.AfterEach
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * 카탈로그 여정(진짜 PostgreSQL · 진짜 보안 체인 · 진짜 장부, 결제사만 가짜 둘: KR → KRW 만 받는 `scripted`, US → USD 만 받는 `usd`):
 * 씨앗 → 나라별 공개 읽기 → 연 가격으로 구독 → 운영자가 행사가를 추가 · 중지 → 기존 구독은 이름 · 금액 그대로, 다른 통화 가격 코드는 결제사가 받지 않아 `ROUTING_FAILED`.
 */
@TestPropertySource(properties = ["skeleton.payment.routing.dev-country-switch=true", "skeleton.payment.routing.country-providers.US=usd"])
@Import(CatalogJourneyIntegrationTest.UsdProvider::class)
class CatalogJourneyIntegrationTest : CheerIntegrationBase() {
    @TestConfiguration(proxyBeanMethods = false)
    class UsdProvider {
        @Bean
        fun usdGateway(store: dev.sumin.skeleton.payment.PaymentLedgerStore): ScriptedGateway =
            ScriptedGateway(providerId = "usd", supportedCurrencies = setOf("USD"), now = { Instant.now() }, openTransaction = store::hasOpenTransaction)
    }

    @AfterEach
    fun removeWhatTheTestAdded() {
        jdbc.sql("delete from catalog_price_provider_refs where price_id in (select id from catalog_prices where code like 'jt-%')").update()
        jdbc.sql("delete from catalog_prices where code like 'jt-%'").update()
    }

    private fun publicRead(country: String? = null): ResultActionsDsl = mvc.get("/api/v1/catalog/products") { country?.let { header("X-Dev-Country", it) } }

    private fun priceCodes(country: String?): List<String> {
        val body = publicRead(country).andExpect { status { isOk() } }.andReturn().response.contentAsString
        return com.jayway.jsonpath.JsonPath.read<List<String>>(body, "$.value.products[*].prices[*].code")
    }

    private fun addPrice(productCode: String, body: String): ResultActionsDsl = post("/api/v1/admin/catalog/products/$productCode/prices", operator, body)

    @Test
    fun `the seed gives each country its own six prices, anonymously`() {
        publicRead().andExpect {
            status { isOk() }
            header { string("Cache-Control", "max-age=60, private") }
            jsonPath("$.value.currency") { value("KRW") }
            jsonPath("$.value.products.length()") { value(3) }
            jsonPath("$.value.products[2].code") { value("pro") }
            jsonPath("$.value.products[2].highlighted") { value(true) }
            jsonPath("$.value.products[0].texts.ko.name") { value("베이직") }
        }
        assertEquals(listOf("basic", "basic-yearly", "standard", "standard-yearly", "pro", "pro-yearly"), priceCodes(null))
        assertEquals(listOf("basic-usd", "basic-yearly-usd", "standard-usd", "standard-yearly-usd", "pro-usd", "pro-yearly-usd"), priceCodes("US"))
        publicRead("US").andExpect { jsonPath("$.value.currency") { value("USD") }; jsonPath("$.value.products[0].prices[1].amount") { value(3000) } }
    }

    @Test
    fun `a yearly subscription survives a sale and a stop - its name, amount and period stay, while new buyers see the current price only`() {
        val started = post("$PAYMENTS/subscriptions", user, """{"planCode":"standard-yearly"}""", key = "cat-key-1").andExpect { status { isCreated() } }
        val id = read(started, "$.value.subscription.id")
        post("$PAYMENTS/subscriptions/$id/activate", user, """{"providerPayload":{"authKey":"auth_1"}}""").andExpect {
            status { isOk() }
            jsonPath("$.value.amount.amount") { value(69000) }
            jsonPath("$.value.period") { value("P1Y") }
        }

        val starts = Instant.now().minus(1, ChronoUnit.MINUTES)
        addPrice("standard", """{"code":"jt-standard-yearly-sale","period":"P1Y","amount":59000,"currency":"KRW","compareAtAmount":69000,"saleStartsAt":"$starts","saleEndsAt":"${starts.plus(1, ChronoUnit.DAYS)}"}""")
            .andExpect { status { isCreated() } }
        publicRead().andExpect { jsonPath("$.value.products[1].prices[1].code") { value("jt-standard-yearly-sale") }; jsonPath("$.value.products[1].prices[1].compareAtAmount") { value(69000) } }
        get("$PAYMENTS/subscriptions/plans", stranger).andExpect { jsonPath("$.value.plans[?(@.code=='jt-standard-yearly-sale')].regularAmount") { value(69000) } }
        post("$PAYMENTS/subscriptions", stranger, """{"planCode":"standard-yearly"}""", key = "cat-key-2").andExpect { status { isUnprocessableEntity() } }   // 행사 중에는 정가로 사는 길이 없다

        post("/api/v1/admin/catalog/prices/jt-standard-yearly-sale/stop", operator).andExpect { status { isOk() }; jsonPath("$.value.selling") { value(false) } }
        assertTrue("standard-yearly" in priceCodes(null))
        get("$PAYMENTS/subscriptions/me", user).andExpect {
            status { isOk() }
            jsonPath("$.value.subscription.planCode") { value("standard-yearly") }
            jsonPath("$.value.subscription.planName") { value("스탠다드") }
            jsonPath("$.value.subscription.amount.amount") { value(69000) }
        }

        // 판매가 끝난 가격도 기존 구독자에게는 그대로다: 월 가격 하나를 만들고 구독한 뒤 중지한다
        addPrice("basic", """{"code":"jt-basic-p3m","period":"P3M","amount":10000,"currency":"KRW"}""").andExpect { status { isCreated() } }
        val other = post("$PAYMENTS/subscriptions", stranger, """{"planCode":"jt-basic-p3m"}""", key = "cat-key-3").andExpect { status { isCreated() } }
        val otherId = read(other, "$.value.subscription.id")
        post("$PAYMENTS/subscriptions/$otherId/activate", stranger, """{"providerPayload":{"authKey":"auth_2"}}""").andExpect { status { isOk() } }
        post("/api/v1/admin/catalog/prices/jt-basic-p3m/stop", operator).andExpect { status { isOk() } }
        get("$PAYMENTS/subscriptions/me", stranger).andExpect { jsonPath("$.value.subscription.planName") { value("베이직") }; jsonPath("$.value.subscription.amount.amount") { value(10000) } }
        post("$PAYMENTS/subscriptions", operator, """{"planCode":"jt-basic-p3m"}""", key = "cat-key-4").andExpect { status { isUnprocessableEntity() } }
    }

    @Test
    fun `a price in a currency the country's provider does not take is refused by the router - and the other country starts it`() {
        post("$PAYMENTS/subscriptions", user, """{"planCode":"basic-usd"}""", key = "cat-key-5").andExpect { status { isBadRequest() }; jsonPath("$.code") { value("PAYMENT.ROUTING_FAILED") } }
        assertEquals(0L, jdbc.sql("select count(*) from payment_subscriptions").query(Long::class.java).single())

        mvc.post("$PAYMENTS/subscriptions") {
            header("Authorization", "Bearer $user")
            header("X-Dev-Country", "US")
            header("Idempotency-Key", "cat-key-6")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"planCode":"basic-usd"}"""
        }.andExpect { status { isCreated() }; jsonPath("$.value.subscription.amount.currency") { value("USD") } }
    }
}
