package dev.sumin.skeleton.common.account

import java.time.Instant

/**
 * 계정에 대한 사실 하나를 모듈이 `account` 를 모르고 묻는 자리(`RoleHolders` 와 같은 선례) — 이벤트의 「새 계정만」 판정이 쓴다.
 * `account` 가 구현한다. 구현이 없으면 부르는 쪽이 그 판정을 건너뛴다(모듈 문서에 적는다).
 */
fun interface AccountFacts {
    /** 가입 시각. 없는 계정이면 null */
    fun createdAt(accountId: String): Instant?
}
