package dev.sumin.skeleton.common.audience

/** [AudienceSource] 가 받는 값의 모양 — 화면이 입력 칸을 고른다 */
enum class AudienceParam { NONE, ROLE, WORKSPACE, TEXT }

/**
 * 「누구에게」를 모듈이 서로를 모르고 말하는 자리(`RoleHolders` 와 같은 선례) — 공지 모듈이 읽고, `account` · `workspace` 가 기본 구현을 내고,
 * 앱은 세그먼트(「지난달 결제한 사람」)를 빈 하나로 더한다. `kind` 는 빈마다 겹치지 않는다.
 *
 * 받는 사람은 계정 id 이고 **활성 계정만** 나온다. [page] 는 계정 id 오름차순 키셋이다: [after] 보다 큰 id 를 [limit] 개까지 — 한 번 넘긴 id 는 다시 나오지 않고
 * (겹침 없음) 쪽마다 새로 읽어도 경계에서 빠지는 사람이 없다. 대상은 미리 굳히지 않는다 - 읽는 동안 가입 · 탈퇴한 사람은 그 시점의 사실을 따른다.
 */
interface AudienceSource {
    val kind: String

    val param: AudienceParam

    /** 쓸 수 없는 [param] 이면 사람이 읽을 사유를, 쓸 수 있으면 null — 호출자가 [count] · [page] 전에 한 번 부른다 */
    fun validate(param: String?): String?

    /** 지금 대상이 몇 명인가 (미리보기 · 시작 때 예상 수). [validate] 를 통과한 값만 준다 */
    fun count(param: String?): Long

    /** [after] 다음 id 부터 오름차순으로 최대 [limit] 명. 끝이면 빈 목록 */
    fun page(param: String?, after: String?, limit: Int): List<String>
}
