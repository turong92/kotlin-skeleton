package dev.sumin.skeleton.badge

import dev.sumin.skeleton.common.reward.RewardPermanentFailure
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BadgeRewardIssuerTest {
    private val store = FakeBadgeStore()
    private val time = MutableTime()
    private val notifier = RecordingNotifier()
    private val definitions = BadgeDefinitions(store, time)
    private val awards = BadgeAwards(store, time) { notifier }
    private val issuer = BadgeRewardIssuer(definitions, awards)

    init {
        definitions.create(BadgeDefinitionInput("first-note", "First note", "wrote a note", null, false, 0))
    }

    @Test
    fun `validate wants a known active badge code`() {
        issuer.validate(mapOf("code" to "first-note"))
        assertFailsWith<IllegalArgumentException> { issuer.validate(emptyMap()) }
        assertFailsWith<IllegalArgumentException> { issuer.validate(mapOf("code" to "nope")) }
        definitions.setStatus(store.findDefinitionByCode("first-note")!!.id, BadgeStatus.ARCHIVED)
        assertFailsWith<IllegalArgumentException> { issuer.validate(mapOf("code" to "first-note")) }
    }

    @Test
    fun `issue is idempotent per grant and returns the award id`() {
        val first = issuer.issue(grant())
        val second = issuer.issue(grant())
        assertEquals(first.resultRef, second.resultRef)
        assertEquals(1, store.events.size)
        assertEquals(BadgeSource.GRANT, store.events.single().source)
    }

    @Test
    fun `an archived or missing badge is a permanent failure`() {
        assertFailsWith<RewardPermanentFailure> { issuer.issue(grant(code = "nope")) }
        definitions.setStatus(store.findDefinitionByCode("first-note")!!.id, BadgeStatus.ARCHIVED)
        assertFailsWith<RewardPermanentFailure> { issuer.issue(grant(id = "rgr_2")) }
    }

    @Test
    fun `describe names the badge in Korean and English with the badge link`() {
        val ko = issuer.describe(mapOf("code" to "first-note"), Locale.KOREAN)
        val en = issuer.describe(mapOf("code" to "first-note"), Locale.ENGLISH)
        assertEquals("First note", ko.title)
        assertEquals("배지를 받았어요", ko.message)
        assertEquals("You earned a badge", en.message)
        assertEquals("/rewards/badges", en.link)
        assertEquals("배지", issuer.describe(mapOf("code" to "gone"), Locale.KOREAN).title)
    }
}
