package dev.sumin.skeleton.app.sample.cheer

import kotlin.test.Test

/**
 * 스토어 키 없이 뜬 견본 앱(기본 설정 그대로): 앱용 인앱결제 HTTP 는 서 있지만 스토어 결제사가 없어 `store` 가 `null` 이고, 지역 스위치는 인앱결제만이다.
 * 구매 확인은 스토어를 못 불러 `503` 이다(앱은 끝내지 않고 다음 실행에 다시 보낸다).
 */
class IapWithoutStoreKeysIntegrationTest : CheerIntegrationBase() {
    @Test
    fun `without store keys the config answers with no store, and a purchase cannot be confirmed`() {
        get("$PAYMENTS/iap/config?platform=ios&storefront=KR", user).andExpect {
            status { isOk() }
            jsonPath("$.value.store") { value(null) }
            jsonPath("$.value.channel") { value("IAP_ONLY") }
            jsonPath("$.value.webCheckoutUrl") { doesNotExist() }
            jsonPath("$.value.products[0].itemRef") { value("cheer-3000") }
            jsonPath("$.value.products[0].storeProductId") { value("com.example.sample.cheer3000") }
        }
        get("$PAYMENTS/iap/config?platform=android", user).andExpect { status { isOk() }; jsonPath("$.value.store") { value(null) } }
        post("$PAYMENTS/iap/appstore/purchases", user, """{"transactionId":"2000000000000999"}""").andExpect { status { isServiceUnavailable() } }
    }
}
