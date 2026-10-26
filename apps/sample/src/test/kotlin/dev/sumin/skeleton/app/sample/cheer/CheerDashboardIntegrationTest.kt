package dev.sumin.skeleton.app.sample.cheer

import com.jayway.jsonpath.JsonPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 견본 앱 조립: 가입 · 활성 · 순매출 · 결제 타일과 퍼널이 진짜 DB 위에서 숫자로 나온다 (키 없이 — 외부 출처 카드는 아직 없다) */
class CheerDashboardIntegrationTest : CheerIntegrationBase() {
    private val dashboard = "/api/v1/admin/dashboard"

    @Test
    fun `the operator sees the db tiles and the cohort funnel with real numbers, everyone else is refused`() {
        buy(user)

        get(dashboard, null).andExpect { status { isUnauthorized() } }
        get(dashboard, user).andExpect { status { isForbidden() } }

        val body = get(dashboard, operator).andExpect { status { isOk() } }.andReturn().response.contentAsString
        val tiles = JsonPath.read<List<String>>(body, "$.value.metrics[*].key")
        assertEquals(listOf("account.signed-up", "auth-session.active", "payment.net-revenue", "payment.paid-count"), tiles)
        assertEquals(listOf("OK", "OK", "OK", "OK"), JsonPath.read<List<String>>(body, "$.value.metrics[*].status"))
        assertEquals("Asia/Seoul", JsonPath.read<String>(body, "$.value.range.zone"))
        assertEquals("KRW", JsonPath.read<String>(body, "$.value.metrics[2].value[0].currency"))
        assertTrue(JsonPath.read<Number>(body, "$.value.metrics[2].value[0].amount").toLong() >= 3000)
        assertTrue(JsonPath.read<Number>(body, "$.value.metrics[3].value[0].amount").toLong() >= 1)
        assertTrue(JsonPath.read<Number>(body, "$.value.metrics[1].value[0].amount").toLong() >= 1)   // 로그인한 계정들
        assertEquals(listOf("account.signed-up", "auth-session.returned", "payment.paid"), JsonPath.read<List<String>>(body, "$.value.funnel.steps[*].key"))
        assertTrue(JsonPath.read<Number>(body, "$.value.funnel.steps[2].reached").toLong() >= 1)
    }
}
