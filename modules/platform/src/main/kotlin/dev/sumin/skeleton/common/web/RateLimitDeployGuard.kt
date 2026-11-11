package dev.sumin.skeleton.common.web

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployGuard

/** 켠 웹 속도 제한(`skeleton.web.rate-limit.enabled`)이 인스턴스별 메모리로 세는 구성을 보호 환경(stage · prod)에서 알린다 - 한도가 인스턴스 수만큼 느슨해지고 재시작에 비워진다. 막지는 않는다 */
class RateLimitDeployGuard(
    /** 저장소는 가드가 돌 때 읽는다 — 빈 생성 순서에 기대지 않는다 */
    private val store: () -> RateLimitStore?,
) : DeployGuard {
    override val name: String = "rate-limit"

    override fun warnings(context: DeployContext): List<String> = buildList {
        if (!context.protectedEnv) return@buildList
        if (store() is InMemoryFixedWindowRateLimitStore) {
            add("skeleton.web.rate-limit counts in each instance's memory, so the limit loosens by the instance count and resets on restart: add modules:redis-rate-limit or define a RateLimitStore bean")
        }
    }
}
