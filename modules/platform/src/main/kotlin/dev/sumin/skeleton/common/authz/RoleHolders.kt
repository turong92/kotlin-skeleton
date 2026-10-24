package dev.sumin.skeleton.common.authz

/** 역할을 가진 활성 계정 수 — `account` 가 구현한다. `authz` 가 `account` 를 모르고도 「가진 계정이 있으면 역할 삭제 불가」를 판정하게 한다 */
fun interface RoleHolders {
    fun holdersOf(role: String): Long
}
