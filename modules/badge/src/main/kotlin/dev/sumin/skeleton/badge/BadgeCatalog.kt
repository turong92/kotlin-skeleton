package dev.sumin.skeleton.badge

/** 사용자 화면의 읽기 — 내 배지(받은 것 · 아직인 것)와 프로필 노출 스위치 */
class BadgeCatalog(private val store: BadgeStore) {
    /** 받은 것(보관된 정의 포함)과 아직인 것(`ACTIVE` 정의 중 못 받은 것). 숨은 정의의 내용을 가리는 것은 응답 쪽의 일이다 */
    fun mine(accountId: String): MyBadges {
        val held = store.activeAwardsOf(accountId)
        val definitions = store.listDefinitions()
        val byId = definitions.associateBy { it.id }
        val earned = held.mapNotNull { award -> byId[award.badgeId]?.let { EarnedBadge(award, it) } }
        val heldIds = held.map { it.badgeId }.toSet()
        return MyBadges(earned, definitions.filter { it.status == BadgeStatus.ACTIVE && it.id !in heldIds })
    }

    /** 본인의 보유 줄만 - 남의 줄 · 없는 줄 · 회수된 줄은 같은 404 */
    fun setVisible(accountId: String, awardId: String, visible: Boolean) {
        if (!store.setVisible(awardId, accountId, visible)) throw BadgeException(BadgeErrorCode.NOT_FOUND, "badge award $awardId not found")
    }

    /** 로그인 없이 읽는 프로필 배지 - 없는 계정도 빈 목록이다(계정이 있는지 알려 주지 않는다) */
    fun publicOf(accountId: String): List<PublicBadge> = store.publicBadgesOf(listOf(accountId))[accountId].orEmpty()
}
