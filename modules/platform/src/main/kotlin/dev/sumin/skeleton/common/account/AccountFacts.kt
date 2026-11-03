package dev.sumin.skeleton.common.account

import java.time.Instant

/**
 * 계정에 대한 사실 하나를 모듈이 `account` 를 모르고 묻는 자리(`RoleHolders` 와 같은 선례) — 이벤트의 「새 계정만」 판정이 쓴다.
 * `account` 가 구현한다. 구현이 없으면 부르는 쪽이 그 판정을 건너뛴다(모듈 문서에 적는다).
 */
fun interface AccountFacts {
    /** 가입 시각. 없는 계정이면 null */
    fun createdAt(accountId: String): Instant?

    /** 확인된(메일함 증명을 마친) 이메일 — 초대 수락처럼 「이 사람이 이 주소의 주인」을 대조할 때 쓴다. 확인 전 · 주소 없음 · 없는 · 지워진 계정은 null. 기본은 null(모르는 구현은 아무 주소도 확인됐다고 하지 않는다) */
    fun verifiedEmail(accountId: String): String? = null

    /** [verifiedEmail] 의 반대 - 이 주소가 **확인된** 살아 있는 계정의 id. 확인 전 · 없는 · 지워진 계정은 null (초대가 「이미 멤버인 주소」를 거를 때). 기본은 null(모르는 구현은 아무 주소도 계정에 잇지 않는다) */
    fun accountIdByVerifiedEmail(email: String): String? = null

    /** 주어진 id 중 **활성** 계정(로그인할 수 있는 계정)만 — 대기 · 정지 · 삭제 유예 · 지워진 · 없는 계정은 빠진다. 계정을 모르는 모듈의 목록(멤버 등)을 거를 때 쓴다. 기본은 전원 통과(모르는 구현은 아무도 거르지 않는다) */
    fun activeAmong(accountIds: Collection<String>): Set<String> = accountIds.toSet()
}
