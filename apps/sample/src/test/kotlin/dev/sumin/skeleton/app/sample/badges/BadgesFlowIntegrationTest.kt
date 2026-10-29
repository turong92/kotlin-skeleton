package dev.sumin.skeleton.app.sample.badges

import com.jayway.jsonpath.JsonPath
import dev.sumin.skeleton.app.sample.cheer.CheerIntegrationBase
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.campaign.Campaigns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired

/**
 * 노트 → 업적 이벤트 → 보상 지급 건 → 일꾼 → 배지(+ 포인트)가 견본 앱 안에서 맞물리는지(진짜 PostgreSQL · 보안 체인 · 큐 일꾼). 씨앗은 [BadgesSeed] 를 직접 돌린다.
 * 로그인 없이 읽는 프로필 배지 경로가 이 앱의 보안 체인에서 실제로 열려 있는지도 여기서 본다.
 */
class BadgesFlowIntegrationTest : CheerIntegrationBase() {
    @Autowired lateinit var definitions: BadgeDefinitions
    @Autowired lateinit var campaigns: Campaigns

    private lateinit var userId: String

    @BeforeEach
    fun seedWorld() {
        listOf(
            "notes", "reward_grants", "badge_award_events", "badge_awards", "badge_definitions", "campaign_progress", "campaigns", "point_entries", "point_balances", "notification_inbox",
        ).forEach { jdbc.sql("delete from $it").update() }
        BadgesSeed(definitions, campaigns).run()
        userId = accountOf("user@example.com")
    }

    private fun note(token: String, n: Int) =
        post("/api/v1/notes", token, """{"title":"note $n","body":"","status":"ACTIVE","pinned":false}""", java.util.UUID.randomUUID().toString()).andExpect { status { isCreated() } }

    private fun earnedCodes(token: String): List<String> =
        JsonPath.read(get("/api/v1/badges/me", token).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.earned[*].code")

    private fun balance(token: String): Long =
        JsonPath.read<Number>(get("/api/v1/points", token).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.value.balance").toLong()

    @Test
    fun `the first note earns the first-note badge and one inbox notice, visible to a logged-out reader`() {
        note(user, 1)
        await { earnedCodes(user).takeIf { it == listOf(BadgesSeed.FIRST_NOTE) } }
        // 받은편지함에는 노트를 만들었다는 알림(NOTE_CREATED)도 있다 - 배지 알림(reward.granted)만 센다
        val granted = await {
            get("/api/v1/notifications", user).andReturn().response.contentAsString
                .let { JsonPath.read<List<String>>(it, "$.values[?(@.type=='reward.granted')].title") }.takeIf { it.isNotEmpty() }
        }
        assertEquals(listOf("첫 노트"), granted)

        val publicBody = get("/api/v1/badges/accounts/$userId", null).andExpect {
            status { isOk() }
            jsonPath("$.value.badges.length()") { value(1) }
            jsonPath("$.value.badges[0].code") { value(BadgesSeed.FIRST_NOTE) }
        }.andReturn().response.contentAsString
        assertFalse(publicBody.contains(BadgesSeed.SECRET_GUEST) || publicBody.contains("비밀 손님"), "an unearned secret badge never shows")
        get("/api/v1/badges/me", null).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `ten notes earn note-10 and 100 points, and the first-note badge is not given twice`() {
        (1..10).forEach { note(user, it) }
        await { earnedCodes(user).takeIf { it.toSet() == setOf(BadgesSeed.FIRST_NOTE, BadgesSeed.NOTE_10) } }
        await { balance(user).takeIf { it == 100L } }
        assertEquals(2L, jdbc.sql("select count(*) from badge_award_events where account_id = :a and action = 'AWARDED'").param("a", userId).query(Long::class.java).single(), "one line per badge, no duplicates")
    }

    @Test
    fun `an operator awards early-bird and revokes it with a reason, and the user's profile follows`() {
        val badgeId = JsonPath.read<List<String>>(get("/api/v1/admin/badges", operator).andExpect { status { isOk() } }.andReturn().response.contentAsString, "$.values[?(@.code=='${BadgesSeed.EARLY_BIRD}')].id").single()
        post("/api/v1/admin/badges/$badgeId/awards", operator, """{"accountIds":["$userId"],"reason":"launch"}""")
            .andExpect { status { isOk() }; jsonPath("$.value.awarded.length()") { value(1) } }
        assertEquals(listOf(BadgesSeed.EARLY_BIRD), earnedCodes(user))
        get("/api/v1/badges/accounts/$userId", null).andExpect { status { isOk() }; jsonPath("$.value.badges.length()") { value(1) } }

        val awardId = JsonPath.read<String>(get("/api/v1/badges/me", user).andReturn().response.contentAsString, "$.value.earned[0].awardId")
        post("/api/v1/admin/badge-awards/$awardId/revoke", operator, """{"reason":""}""").andExpect { status { isBadRequest() } }
        post("/api/v1/admin/badge-awards/$awardId/revoke", operator, """{"reason":"abuse"}""").andExpect { status { isOk() } }
        assertTrue(earnedCodes(user).isEmpty())
        get("/api/v1/badges/accounts/$userId", null).andExpect { jsonPath("$.value.badges.length()") { value(0) } }
        get("/api/v1/admin/badge-awards?accountId=$userId", operator).andExpect { status { isOk() }; jsonPath("$.values[0].action") { value("REVOKED") } }
        post("/api/v1/admin/badges/$badgeId/awards", user, """{"accountIds":["$userId"]}""").andExpect { status { isForbidden() } }
    }
}
