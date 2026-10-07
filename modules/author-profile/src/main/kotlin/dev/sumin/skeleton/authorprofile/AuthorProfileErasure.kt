package dev.sumin.skeleton.authorprofile

import dev.sumin.skeleton.common.erasure.AccountErasureListener
import dev.sumin.skeleton.common.erasure.ErasureRequest

/**
 * 계정이 완전히 지워질 때 그 계정의 프로필 행을 **모두 지운다**(지워진 프로필 포함 — 형제 관계가 `author_profiles` 표에는 남지 않는다(글 쪽 `author_ref` 는 프로필마다 다른 id 로 남는다)). 멱등이다.
 * 글 · 댓글(board)의 `author_ref` 는 건드리지 않는다 — 프로필마다 다른 id 가 남고, 디렉터리가 이제 없는 프로필 id 에 이름을 돌려주지 않을 뿐이다.
 * "삭제된 사용자" 표시는 board 의 고리가 `author_id` 에 톰스톤을 쓰는 것에서 나온다 — 두 고리는 서로의 순서에 기대지 않는다.
 */
class AuthorProfileErasureListener(private val repository: AuthorProfileRepository) : AccountErasureListener {
    override val name: String = "author-profile"

    override fun erase(request: ErasureRequest) {
        repository.deleteByAccount(request.accountId)
    }
}
