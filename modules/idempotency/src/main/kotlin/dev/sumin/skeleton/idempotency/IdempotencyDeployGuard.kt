package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/** stage · prod 에서 멱등 키가 인스턴스끼리 갈라지거나 재시작에 사라지는 구성을 막는다 - 같은 키가 다른 인스턴스로 가면 다시 실행된다 */
class IdempotencyDeployGuard(
    /** 저장소는 가드가 돌 때 읽는다 — 빈 생성 순서에 기대지 않는다 */
    private val store: () -> IdempotencyStore?,
) : DeployGuard {
    override val name: String = "idempotency"

    override fun problems(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) return@buildList
        if (store() is InMemoryIdempotencyStore) {
            add("idempotency uses the in-memory IdempotencyStore (a repeated Idempotency-Key that reaches another instance, or one after a restart, runs the command again): add modules:idempotency-jdbc or define your own IdempotencyStore bean")
        }
    }
}
