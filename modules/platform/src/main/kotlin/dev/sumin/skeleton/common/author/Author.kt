package dev.sumin.skeleton.common.author

/**
 * 계정 id 를 사람이 읽는 이름으로 바꾸는 고리 — 글 · 댓글처럼 작성자 id 를 들고 있는 모듈이 화면용 이름을 얻는 곳이다.
 * 계정 모듈과 board 같은 모듈이 **서로의 타입을 모르고** 만나는 자리라서 platform 에 있다 ([dev.sumin.skeleton.common.erasure.AccountErasureListener] 와 같은 방식):
 * `account` 가 기본 구현을 내고(닉네임 · 꼬리표), 앱이 같은 타입의 빈을 두면 그것이 이긴다 (예: 돌판마다 다른 닉네임 — [AuthorContext.scope]).
 * 구현이 없으면 [NONE] — 이름이 없을 뿐 모듈은 그대로 돈다.
 */
fun interface AuthorDirectory {
    /**
     * [ids] 의 이름을 **한 번에** 돌려준다 — 호출하는 쪽은 요청 하나에 한 번만 부른다(목록 · 스레드 전체의 작성자를 모아서). 모르는 id 는 결과에 없어도 된다.
     * 구현은 느리거나 던질 수 있다는 전제로 부르는 쪽이 감싼다. 지워진 작성자(톰스톤)는 넘기지 않는다.
 * 계정 톰스톤(`deleted:…`)은 넘기지 않는다. 지워진 **프로필**은 넘겨도 되고 카드 `deleted=true` 로 답한다(그때 [AuthorCard.name] 은 null).
     */
    fun resolve(ids: Collection<String>, context: AuthorContext): Map<String, AuthorCard>

    companion object {
        /** 이름을 모른다 — 구현이 없을 때의 기본 */
        val NONE: AuthorDirectory = AuthorDirectory { _, _ -> emptyMap() }
    }
}

/**
 * 이름을 묻는 자리. [source] 는 부르는 모듈(`board`), [scope] 는 그 안의 범위(게시판 코드) — 앱이 "이 게시판에서의 닉네임" 을 돌려줄 수 있게 한다.
 */
data class AuthorContext(val source: String, val scope: String? = null)

/**
 * 화면에 보일 작성자. [name] 이 없으면 이름 없음, [tag] 는 같은 이름을 구분하는 4자리 꼬리표(없을 수 있다),
 * [deleted] 는 지워진 프로필(기본 false — 기존 구현은 그대로 컴파일된다)
 */
data class AuthorCard(val name: String?, val tag: String? = null, val deleted: Boolean = false)

/**
 * 이 계정이 이 공개 작성자 id 로 써도 되는가 — 글을 쓰는 모듈이 쓰기 직전에 묻는다.
 * 빈이 없으면 공개 작성자 id 는 계정 id 다(이 기능 이전의 동작). 구현은 없는 id · 남의 id · 지워진 id 를 구별하지 않고 false 를 돌려준다.
 */
fun interface AuthorIdentities {
    fun allows(accountId: String, authorId: String, context: AuthorContext): Boolean
}

/**
 * 공개 작성자 id 없이 저장된 옛 행을 계정의 첫 프로필로 옮긴다 — 글을 들고 있는 모듈이 구현한다
 * ([dev.sumin.skeleton.common.erasure.AccountErasureListener] 와 같은 방식). 멱등이다.
 */
interface AuthorAdoptionListener {
    /** 이 구현의 이름(로그 · 진행 표시용) */
    val name: String

    /**
     * 아직 옮기지 않은 행을 가진 계정 id (지워진 계정 제외), **앞쪽부터** 최대 [limit] 개.
     * 실행기 계약: 한 바퀴에 진행(`adopt` 가 1 이상)이 없으면 실행기는 그 바퀴에서 멈춘다 — 던지거나 0 을 돌려주는 계정이
     * 뒤 계정을 굶기지 않게, 계정별 예외는 삼키고 로그를 남기며 다음 기동 때 다시 한다.
     */
    fun pendingAccounts(limit: Int): List<String>

    /** [accountId] 의 옛 행을 공개 작성자 id [authorId] 로 옮기고 옮긴 행 수를 돌려준다. 이미 옮겼으면 0 */
    fun adopt(accountId: String, authorId: String): Int
}
