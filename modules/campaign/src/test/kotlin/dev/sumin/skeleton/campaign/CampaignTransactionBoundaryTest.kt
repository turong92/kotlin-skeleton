package dev.sumin.skeleton.campaign

import dev.sumin.skeleton.common.account.AccountFacts
import dev.sumin.skeleton.common.time.TimeProvider
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 출석은 지급 건을 **기록**하는 데서 끝난다 - 쿠폰 · 포인트를 모른다(그 모듈들은 이 모듈의 클래스패스에 없다). 지급기 빈이 하나도 없어도 출석은 커밋되고 보상 줄은 종류 이름으로 나간다.
 */
class CampaignTransactionBoundaryTest {
    private val store = FakeCampaignStore()
    private val grants = RecordingGrants()
    private val time = TimeProvider.fixed(Instant.parse("2026-10-28T01:00:00Z"))
    private val config = mapOf("daily" to listOf(mapOf("type" to "point", "params" to mapOf("amount" to "10"))))

    @Test
    fun `with no issuer bean the check-in still commits and records the grant`() {
        store.insert(Campaign("cmp_1", "attendance", "Daily", true, Instant.parse("2026-01-01T00:00:00Z"), null, config, time.now(), time.now()))
        val service = AttendanceService(store, grants, { emptyList() }, CampaignZone(ZoneId.of("Asia/Seoul")), time)

        val result = service.checkIn("acc_1", "cmp_1")

        assertEquals(false, result.alreadyChecked)
        assertEquals(listOf(RewardLine("point", "point")), result.rewards)
        assertEquals(listOf(Triple("acc_1", "point", "attendance/cmp_1:acc_1:2026-10-28:daily:0")), grants.recorded)
    }

    @Test
    fun `the reward name is made in the checking-in account's language even when the JVM default is English`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        try {
            store.insert(Campaign("cmp_1", "attendance", "Daily", true, Instant.parse("2026-01-01T00:00:00Z"), null, config, time.now(), time.now()))
            val issuer = PointIssuer()
            val facts = object : AccountFacts {
                override fun createdAt(accountId: String): Instant? = null
                override fun locale(accountId: String): java.util.Locale? = if (accountId == "acc_ko") java.util.Locale.KOREAN else null
            }
            val zone = CampaignZone(ZoneId.of("Asia/Seoul"))

            AttendanceService(store, grants, { listOf(issuer) }, zone, time, { facts }).checkIn("acc_ko", "cmp_1")
            AttendanceService(store, grants, { listOf(issuer) }, zone, time, { facts }).checkIn("acc_other", "cmp_1")

            assertEquals(listOf(java.util.Locale.KOREAN, java.util.Locale.ENGLISH), issuer.describedIn)
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `the zone ladder is the campaign zone, then the app default, then Asia Seoul`() {
        assertEquals(ZoneId.of("America/New_York"), CampaignZone.resolve("America/New_York", "Europe/Paris").zone)
        assertEquals(ZoneId.of("Europe/Paris"), CampaignZone.resolve(null, "Europe/Paris").zone)
        assertEquals(ZoneId.of("Asia/Seoul"), CampaignZone.resolve("", null).zone)
    }
}
