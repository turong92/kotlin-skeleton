package dev.sumin.skeleton.account

import dev.sumin.skeleton.common.author.AuthorCard
import dev.sumin.skeleton.common.author.AuthorContext
import dev.sumin.skeleton.common.author.AuthorDirectory

/**
 * 계정의 닉네임 · 꼬리표를 작성자 이름으로 내는 기본 [AuthorDirectory] — 글 · 댓글 모듈(board)이 계정 모듈을 모른 채 이름을 얻는다.
 * 쿼리는 [AccountRepository.namesOf] 한 번. 어느 범위에서 묻든([AuthorContext]) 같은 이름을 준다 — 범위마다 다른 닉네임이 필요한 앱이 자기 [AuthorDirectory] 빈으로 바꾼다.
 *
 * 상태별: ERASED 는 이름이 없다(개인정보를 지웠다 — 글 쪽은 이미 톰스톤) · 유예 중(DELETED) · 정지(SUSPENDED) 계정은 **행이 살아 있으니 이름 그대로** —
 * 유예 중인 주인은 돌아올 수 있고, 정지된 계정의 글을 "누가 썼는지" 숨기면 운영자도 읽기 어렵다.
 */
class AccountAuthorDirectory(private val accounts: AccountRepository) : AuthorDirectory {
    override fun resolve(ids: Collection<String>, context: AuthorContext): Map<String, AuthorCard> {
        if (ids.isEmpty()) return emptyMap()
        return accounts.namesOf(ids).mapNotNull { (id, row) ->
            if (row.status == AccountStatus.ERASED || row.displayName == null) null
            else id to AuthorCard(row.displayName, DisplayNames.visibleTag(row.displayTag))
        }.toMap()
    }
}
