package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.AccountRepository
import dev.sumin.skeleton.account.events.AccountEventPublisher
import dev.sumin.skeleton.account.events.AccountEventType

/** 한 계정의 프로필 전부(지워진 것 포함). 관리자 응답에만 나가는 모양이다 */
data class AuthorLinks(val accountId: String, val profiles: List<AuthorProfile>) {
    /** 계정 id 와 프로필 id 의 쌍이 로그 · 예외 메시지에 실리지 않게 — 개수만 */
    override fun toString(): String = "AuthorLinks(profiles=${profiles.size})"
}

/**
 * 관리자의 연결 조회(스펙 §7.9) — 프로필 id → 그 계정의 프로필들, 계정 id → 그 계정의 프로필들.
 * 호출마다 `AUTHOR_LINK_VIEWED`(대상 계정, `by` = 관리자)를 낸다. **detail 에도 로그에도 프로필 id 를 싣지 않는다**(이 클래스는 로그를 남기지 않는다).
 * 없는 프로필 id · 없는 계정 id 는 [AuthorProfileErrorCode.NOT_FOUND] 이고 **이벤트를 남기지 않는다** — 헛짚은 조회가 감사 표에 흔적(존재 여부)을 남기지 않게.
 * 권한 확인(관리자 + 매 호출 저장소의 역할 재확인)은 부르는 쪽(컨트롤러)의 몫이다.
 * 이 클래스를 교체하는 앱은 **감사 발행의 책임도 함께 가져간다** — 교체본이 `AUTHOR_LINK_VIEWED` 를 내지 않으면 조회가 기록 없이 나간다.
 * 기록 실패(듣는 쪽이 던짐)는 발행자가 warn 로그만 남기고 삼킨다 — 조회는 그대로 나간다.
 */
class AuthorLinkLookup(
    private val repository: AuthorProfileRepository,
    private val accounts: () -> AccountRepository,
    private val events: AccountEventPublisher,
) {
    fun byProfile(adminId: String, profileId: String): AuthorLinks {
        val accountId = repository.find(profileId)?.accountId ?: throw AuthorProfileException(AuthorProfileErrorCode.NOT_FOUND)
        return viewed(adminId, accountId)
    }

    fun byAccount(adminId: String, accountId: String): AuthorLinks {
        if (accounts().findById(accountId) == null) throw AuthorProfileException(AuthorProfileErrorCode.NOT_FOUND)
        return viewed(adminId, accountId)
    }

    private fun viewed(adminId: String, accountId: String): AuthorLinks {
        val links = AuthorLinks(accountId, repository.findAllOf(accountId))
        events.publish(AccountEventType.AUTHOR_LINK_VIEWED, accountId, detail = mapOf("by" to adminId))
        return links
    }
}
