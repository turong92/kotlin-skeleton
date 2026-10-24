package dev.sumin.skeleton.common.authz

/** 역할을 가진 계정 — `account` 가 구현한다. `authz` 가 `account` 를 모르고도 「가진 계정이 있으면 역할 삭제 불가」를 판정하게 한다 */
fun interface RoleHolders {
    /** 활성 계정 수 — 0 보다 크면 역할을 지울 수 없다 */
    fun holdersOf(role: String): Long

    /**
     * 역할을 지울 때 같은 트랜잭션에서 **모든 계정**(정지 · 대기 · 삭제 유예 포함)의 그 역할 배정을 거둔다 — 같은 이름으로 다시 만든 역할을
     * 아무도 주지 않은 계정이 물려받지 못하게. [by] 는 지운 운영자의 계정 id. 거둔 계정 수를 돌려준다.
     */
    fun revokeFromAll(role: String, by: String): Int = 0
}
