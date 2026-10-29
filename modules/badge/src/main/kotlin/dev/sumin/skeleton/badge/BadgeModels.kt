package dev.sumin.skeleton.badge

import java.time.Instant

/** 정의 상태 — `ACTIVE` 만 새로 수여된다. `ARCHIVED` 는 받은 사람에게는 그대로 보이고 더 줄 수 없다 */
enum class BadgeStatus { ACTIVE, ARCHIVED }

/** 보유 상태 — 회수하면 `REVOKED`, 다시 받으면 같은 줄이 `ACTIVE` 로 돌아온다 */
enum class BadgeAwardStatus { ACTIVE, REVOKED }

/** 누가 줬나 — 앱 코드(`APP`) · 운영자(`ADMIN`) · 보상 지급기(`GRANT`, 지급 건 id) */
enum class BadgeSource { APP, ADMIN, GRANT }

enum class BadgeAction { AWARDED, REVOKED }

/** 배지 정의. [code] 는 앱 코드와 보상 설정이 부르는 안정된 이름이라 만든 뒤 바꾸지 않는다. [imageUrl] 은 그림 주소 문자열뿐이다(올리기는 없다) */
data class BadgeDefinition(
    val id: String,
    val code: String,
    val name: String,
    val description: String,
    val imageUrl: String?,
    val hidden: Boolean,
    val sortOrder: Int,
    val status: BadgeStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 계정이 이 배지를 지금 가졌나 — (배지, 계정)당 한 줄 */
data class BadgeAward(
    val id: String,
    val badgeId: String,
    val accountId: String,
    val status: BadgeAwardStatus,
    /** 본인이 프로필에 보일지 */
    val visible: Boolean,
    val awardedAt: Instant,
    val revokedAt: Instant?,
)

/** 수여 · 회수 내역 한 줄 — 덧붙이기만 한다 */
data class BadgeAwardEvent(
    val id: String,
    val awardId: String,
    val badgeId: String,
    val accountId: String,
    val action: BadgeAction,
    val source: BadgeSource,
    val sourceRef: String?,
    val actorId: String?,
    val reason: String?,
    val createdAt: Instant,
)

/** 프로필 같은 공개 자리에 나가도 되는 배지의 모양 — 이 넷뿐이다 */
data class PublicBadge(val code: String, val name: String, val description: String, val imageUrl: String?)

/** 앱이 [Badges.award] 로 받는 결과. [newlyAwarded] 가 false 면 이미 가지고 있었다 */
data class BadgeHolding(val awardId: String, val code: String, val awardedAt: Instant, val newlyAwarded: Boolean)

/** 내 배지 화면의 받은 것 한 개 */
data class EarnedBadge(val award: BadgeAward, val definition: BadgeDefinition)

/** 내 배지 화면 — 받은 것과 아직인 것(`ACTIVE` 정의 중 못 받은 것). 숨은 정의의 내용을 가리는 것은 응답 쪽의 일이다 */
data class MyBadges(val earned: List<EarnedBadge>, val locked: List<BadgeDefinition>)

/** 수동 수여 결과 */
data class AdminAwardResult(val awarded: List<String>, val alreadyHeld: List<String>)

data class AwardHistoryRow(val event: BadgeAwardEvent, val badge: BadgeDefinition?)

data class AwardHistoryPage(val items: List<AwardHistoryRow>, val nextCursor: String?)
