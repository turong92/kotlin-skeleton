package dev.sumin.skeleton.campaign.referral

import dev.sumin.skeleton.campaign.Campaign
import dev.sumin.skeleton.common.deploy.DeployContext
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 같은 네트워크 확인을 켠 초대 이벤트가 돌고 있는데 클라이언트 IP 규칙이 없으면 경고만 한다 - 막지 않는다 */
class ReferralDeployGuardTest {
    private val at = Instant.parse("2026-10-30T00:00:00Z")
    private val point = listOf(mapOf("type" to "point", "params" to mapOf("amount" to "50")))
    private val context = DeployContext(null, emptySet())

    private fun campaign(kind: String = "referral", vararg extra: Pair<String, Any?>) =
        Campaign("cmp_1", kind, "Invite", true, at, null, mapOf("inviter" to point, "qualifyOn" to "SIGNAL") + extra, at, at)

    private fun guard(active: List<Campaign>, clientIpMode: Boolean) = ReferralDeployGuard({ active }, { clientIpMode })

    @Test
    fun `an active review-same-network campaign without a client ip mode warns by property name, and never blocks`() {
        val g = guard(listOf(campaign(extra = arrayOf("reviewSameNetwork" to true))), clientIpMode = false)

        assertEquals(emptyList(), g.problems(context))
        val warning = g.warnings(context).single()
        assertTrue("skeleton.web.client-ip.mode" in warning, warning)
    }

    @Test
    fun `no warning when the mode is set, the switch is off, or the campaign is not a referral one`() {
        assertEquals(emptyList(), guard(listOf(campaign(extra = arrayOf("reviewSameNetwork" to true))), clientIpMode = true).warnings(context))
        assertEquals(emptyList(), guard(listOf(campaign()), clientIpMode = false).warnings(context))
        assertEquals(emptyList(), guard(listOf(campaign(kind = "attendance", extra = arrayOf("reviewSameNetwork" to true))), clientIpMode = false).warnings(context))
        assertEquals(emptyList(), guard(emptyList(), clientIpMode = false).warnings(context))
    }

    @Test
    fun `a failing store read or a stored config that does not parse never blocks the start`() {
        assertEquals(emptyList(), ReferralDeployGuard({ error("db down") }, { false }).warnings(context))
        assertEquals(emptyList(), guard(listOf(campaign(extra = arrayOf("reviewSameNetwork" to "yes"))), clientIpMode = false).warnings(context))
    }
}
