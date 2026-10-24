package dev.sumin.skeleton.common.authz

/** 토큰의 역할을 매핑에 통과시킨다 — 회수는 토큰 만료까지 늦는다. `account` 가 있으면 저장소를 다시 읽는 구현이 먼저 선다 */
class TokenOperatorAccess(private val source: RolePermissionSource) : AbstractOperatorAccess() {
    override fun compute(ref: OperatorRef): Set<String> =
        ref.tokenRoles.flatMapTo(sortedSetOf()) { source.permissionsOf(it) }
}
