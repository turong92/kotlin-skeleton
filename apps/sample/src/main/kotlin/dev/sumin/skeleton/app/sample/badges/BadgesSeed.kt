package dev.sumin.skeleton.app.sample.badges

import dev.sumin.skeleton.badge.BadgeDefinitionInput
import dev.sumin.skeleton.badge.BadgeDefinitions
import dev.sumin.skeleton.campaign.CampaignInput
import dev.sumin.skeleton.campaign.Campaigns
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬 프로필의 씨앗 - 배지 정의 넷(「첫 노트」 · 「노트 10개」 · 「얼리 버드」 · 숨은 「비밀 손님」)과 업적 이벤트 하나(수치 `notes.created`: 1 → 「첫 노트」, 10 → 「노트 10개」 + 100포인트).
 * 「얼리 버드」 · 「비밀 손님」은 운영자 수동 수여로 시연한다. 코드로 찾아 **없는 것만** 만든다 - 다시 떠도 늘지 않고, 운영 화면에서 고친 것을 덮지 않는다.
 * 배지와 포인트를 같이 주는 것은 배지 모듈이 아니라 업적 단계의 보상 목록에 나란히 적은 이 이벤트 설정이다.
 */
@Component
@Profile("local")
class BadgesSeedRunner(
    private val definitions: BadgeDefinitions,
    private val campaigns: Campaigns,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = BadgesSeed(definitions, campaigns).run()
}

class BadgesSeed(
    private val definitions: BadgeDefinitions,
    private val campaigns: Campaigns,
    private val now: () -> Instant = { Instant.now() },
) {
    fun run() {
        ensure(FIRST_NOTE, "첫 노트", "노트를 처음 만들었어요", hidden = false, order = 1)
        ensure(NOTE_10, "노트 10개", "노트를 열 개 만들었어요", hidden = false, order = 2)
        ensure(EARLY_BIRD, "얼리 버드", "초기에 함께해 주셨어요", hidden = false, order = 3)
        ensure(SECRET_GUEST, "비밀 손님", "운영자가 건네는 숨은 배지", hidden = true, order = 4)

        if (campaigns.list().none { it.name == NOTES_ACHIEVEMENT }) {
            campaigns.create(
                "achievement",
                CampaignInput(
                    NOTES_ACHIEVEMENT, now().truncatedTo(ChronoUnit.SECONDS).minus(1, ChronoUnit.MINUTES), null,
                    mapOf(
                        "metric" to "notes.created",
                        "milestones" to listOf(
                            mapOf("value" to 1, "rewards" to listOf(reward("badge", "code" to FIRST_NOTE))),
                            mapOf("value" to 10, "rewards" to listOf(reward("badge", "code" to NOTE_10), reward("point", "amount" to "100"))),
                        ),
                    ),
                ),
            )
        }
    }

    private fun ensure(code: String, name: String, description: String, hidden: Boolean, order: Int) {
        if (definitions.findByCode(code) == null) definitions.create(BadgeDefinitionInput(code, name, description, null, hidden, order))
    }

    private fun reward(type: String, vararg params: Pair<String, String>) = mapOf("type" to type, "params" to params.toMap())

    companion object {
        const val FIRST_NOTE = "first-note"
        const val NOTE_10 = "note-10"
        const val EARLY_BIRD = "early-bird"
        const val SECRET_GUEST = "secret-guest"
        const val NOTES_ACHIEVEMENT = "노트 업적"
    }
}
