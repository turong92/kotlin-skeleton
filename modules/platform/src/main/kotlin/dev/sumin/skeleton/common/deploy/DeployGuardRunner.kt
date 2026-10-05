package dev.sumin.skeleton.common.deploy

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton

/**
 * 모든 빈이 만들어진 직후(웹 서버가 열리기 전)에 [DeployGuard] 들을 돌린다: problems 가 있으면 기동 실패, warnings 는 WARN 로그,
 * 그리고 어떤 가드가 있는지 INFO 한 덩어리로 남긴다 (액추에이터 없이 보는 목록). 결과는 [report].
 */
class DeployGuardRunner(
    private val guards: List<DeployGuard>,
    private val context: DeployContext,
) : SmartInitializingSingleton {
    @Volatile
    var report: DeployGuardReport? = null
        private set

    override fun afterSingletonsInstantiated() {
        val result = DeployGuardReport.evaluate(guards, context)
        report = result
        log.info(result.summary())
        result.warnings.forEach { log.warn("deploy guard warning [{}]: {}", it.guard, it.message) }
        if (result.problems.isNotEmpty()) throw DeployGuardViolationException(result.problems)
    }

    private companion object {
        val log = LoggerFactory.getLogger(DeployGuardRunner::class.java)
    }
}
