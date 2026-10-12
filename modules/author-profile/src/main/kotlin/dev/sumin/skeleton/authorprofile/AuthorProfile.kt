package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.account.DisplayNames
import dev.sumin.skeleton.common.id.SortableIds
import java.time.Instant

enum class AuthorProfileStatus { ACTIVE, DELETED }

/**
 * 작성자 프로필 한 줄 — 한 계정이 글 · 댓글마다 골라 쓰는 이름. [id] 가 **공개 작성자 id** 다 (`prf_` + 무작위 128비트: 순번 · 시각 · 계정에서 파생하지 않는다).
 * [accountId] 는 비공개다 — 이 모듈 밖으로 나가는 응답 · 알림 · 로그에 [id] 와 함께 싣지 않는다.
 *
 * 지워진 프로필(DELETED)은 행이 남고 [name] · [nameKey] · [tag] 가 NULL 이다(꼬리표가 풀린다). 이름 없는 프로필(ACTIVE 인데 [name] 이 NULL)은
 * 닉네임이 없던 계정의 옛 글을 넘겨받은 첫 프로필뿐이다.
 */
data class AuthorProfile(
    val id: String,
    val accountId: String,
    /** 계정의 **첫 프로필**이면 계정 id, 아니면 null — 저장소가 계정당 하나로 지킨다(지워도 자리가 남아 첫 프로필을 두 번 만들지 않는다) */
    val primaryOf: String?,
    val name: String?,
    /** 이름의 비교용 키([dev.sumin.skeleton.account.DisplayNameRules.key]) — (키, 꼬리표)가 프로필 표 안에서 유일하다 */
    val nameKey: String?,
    /** 닉네임 방식이 정하는 꼬리표: NONE 은 null, UNIQUE 는 [DisplayNames.NO_TAG], TAGGED 는 `0001`..`9999` — 계정의 `display_tag` 와 같은 규칙 */
    val tag: String?,
    val status: AuthorProfileStatus,
    /** 첫 프로필만: 옛 글을 넘겨받은 시각 */
    val adoptedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    /** 화면에 보일 꼬리표 (없거나 UNIQUE 의 고정값이면 null) */
    val visibleTag: String? get() = DisplayNames.visibleTag(tag)

    /** 계정 id 와 프로필 id 의 쌍이 로그 · 오류 메시지에 실리지 않게 — [accountId] · [primaryOf] 는 찍지 않는다 */
    override fun toString(): String = "AuthorProfile(id=$id, status=$status)"
}

object AuthorProfileIds {
    const val PREFIX = "prf_"

    fun newId(): String = SortableIds.system.newId(PREFIX)

    /** 프로필 id 의 **모양**인가 — 있는 프로필인지는 저장소가 안다. 아니면 옛 행의 계정 id 같은, 다른 디렉터리의 몫이다 */
    fun isProfileId(id: String): Boolean = id.startsWith(PREFIX)
}
