package dev.sumin.skeleton.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 초대 이벤트 설정 칸 셋(신호 이름 · 보류 일수 · 같은 네트워크 확인)의 경계. 칸을 안 쓰면 지금과 같다 */
class ReferralConfigTest {
    private val point = listOf(mapOf("type" to "point", "params" to mapOf("amount" to "50")))
    private fun raw(vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("inviter" to point, "qualifyOn" to "SIGNAL") + extra

    private fun invalid(config: Map<String, Any?>) {
        val e = assertFailsWith<CampaignException> { ReferralKind().validate(config, mapOf("point" to PointIssuer())) }
        assertEquals(CampaignErrorCode.CONFIG_INVALID, e.errorCode)
    }

    @Test
    fun `a config that does not use the new fields reads as before`() {
        val parsed = ReferralConfig.parse(raw())
        assertNull(parsed.qualifyEvent)
        assertEquals(0, parsed.holdDays)
        assertFalse(parsed.reviewSameNetwork)
    }

    @Test
    fun `qualifyEvent must be a lowercase name and only with SIGNAL`() {
        assertEquals("first-payment", ReferralConfig.parse(raw("qualifyEvent" to "first-payment")).qualifyEvent)
        assertEquals("a".repeat(64), ReferralConfig.parse(raw("qualifyEvent" to "a".repeat(64))).qualifyEvent)
        assertNull(ReferralConfig.parse(raw("qualifyEvent" to "  ")).qualifyEvent)   // 비운 칸은 안 쓴 것
        invalid(raw("qualifyEvent" to "a".repeat(65)))
        invalid(raw("qualifyEvent" to "First-Payment"))
        invalid(raw("qualifyEvent" to "first payment"))
        invalid(raw("qualifyEvent" to 7))
        invalid(raw("qualifyEvent" to "first-payment", "qualifyOn" to "CLAIM"))
    }

    @Test
    fun `holdDays is 0 to 90`() {
        assertEquals(0, ReferralConfig.parse(raw("holdDays" to 0)).holdDays)
        assertEquals(90, ReferralConfig.parse(raw("holdDays" to 90)).holdDays)
        invalid(raw("holdDays" to -1))
        invalid(raw("holdDays" to 91))
        invalid(raw("holdDays" to 1.5))
        invalid(raw("holdDays" to "7"))
    }

    @Test
    fun `reviewSameNetwork is a boolean`() {
        assertTrue(ReferralConfig.parse(raw("reviewSameNetwork" to true)).reviewSameNetwork)
        assertFalse(ReferralConfig.parse(raw("reviewSameNetwork" to false)).reviewSameNetwork)
        invalid(raw("reviewSameNetwork" to "yes"))
        invalid(raw("reviewSameNetwork" to 1))
    }
}
