package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory
import dev.sumin.skeleton.common.author.AuthorIdentities

/**
 * 공개 작성자 id(`prf_…`)를 화면용 이름으로 — 살아 있는 프로필은 이름 + 꼬리표, 지워진 프로필은 `AuthorCard(null, null, deleted = true)`.
 * `prf_` 가 아닌 id(아직 넘겨받지 않은 옛 행의 계정 id)는 [fallback] 에 **그대로** 넘긴다 — `account` 의 기본 디렉터리다.
 * 없는 프로필 id · 이름 없는 첫 프로필은 결과에 없다 (이름을 모른다).
 * 프로필 조회는 요청 하나에 쿼리 한 번이다 ([AuthorProfileRepository.findAll]).
 */
class AuthorProfileDirectory(
    private val repository: AuthorProfileRepository,
    private val fallback: AuthorDirectory,
) : AuthorDirectory {
    override fun resolve(ids: Collection<String>, context: AuthorContext): Map<String, AuthorCard> {
        if (ids.isEmpty()) return emptyMap()
        val (profileIds, others) = ids.partition(AuthorProfileIds::isProfileId)
        val cards = LinkedHashMap<String, AuthorCard>()
        if (others.isNotEmpty()) cards += fallback.resolve(others, context)
        if (profileIds.isNotEmpty()) {
            repository.findAll(profileIds).forEach { (id, profile) ->
                when {
                    profile.status == AuthorProfileStatus.DELETED -> cards[id] = AuthorCard(null, null, deleted = true)
                    profile.name != null -> cards[id] = AuthorCard(profile.name, profile.visibleTag)
                }
            }
        }
        return cards
    }
}

/**
 * 이 계정이 이 공개 작성자 id 로 써도 되는가 — 살아 있는 **내** 프로필만 참이다. 남의 · 없는 · 지워진 id (계정 id 를 내민 경우 포함)는 구별 없이 같은 false 다.
 * [AuthorContext] 는 보지 않는다 — "이 프로필은 이 돌판에서만" 같은 제한은 앱이 이 빈을 감싸서 건다.
 */
class AuthorProfileIdentities(private val repository: AuthorProfileRepository) : AuthorIdentities {
    override fun allows(accountId: String, authorId: String, context: AuthorContext): Boolean {
        if (!AuthorProfileIds.isProfileId(authorId)) return false
        val profile = repository.find(authorId) ?: return false
        return profile.accountId == accountId && profile.status == AuthorProfileStatus.ACTIVE
    }
}
