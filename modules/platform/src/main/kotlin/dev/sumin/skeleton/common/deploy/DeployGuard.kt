package dev.sumin.skeleton.common.deploy

/**
 * 모듈이 기여하는 배포 가드. 빈으로 등록하면 [DeployGuardRunner] 가 모아 기동 때 돌린다.
 *
 * - [problems]: 이 환경에서 서비스가 서면 안 되는 것 — 하나라도 있으면 기동 실패. **자기 환경 판단은 가드 몫이다**:
 *   새 가드는 `context.protectedEnv`(stage · prod)를 보고, 프로필로 보호하던 가드는 `context.protectedBy(자기 프로필 목록)` 를 본다.
 * - [warnings]: 서지만 알아 둘 것 — 로그로만 남는다.
 *
 * 메시지에는 **환경변수 · 속성 이름만** 쓰고 값은 싣지 않는다 (로그 · 실패 화면에 비밀이 남지 않게).
 */
interface DeployGuard {
    /** 요약과 실패 화면에 나오는 짧은 이름 (모듈 이름 권장) */
    val name: String

    fun problems(context: DeployContext): List<String> = emptyList()

    fun warnings(context: DeployContext): List<String> = emptyList()
}

data class DeployFinding(val guard: String, val message: String)

/** 가드가 낸 문제로 기동이 막혔다. 하나뿐이면 메시지는 그 문제 그대로다. */
class DeployGuardViolationException(val problems: List<DeployFinding>) :
    IllegalStateException(
        if (problems.size == 1) problems.single().message else problems.joinToString("; ") { "[${it.guard}] ${it.message}" },
    )

data class DeployGuardReport(
    val context: DeployContext,
    val guards: List<String>,
    val problems: List<DeployFinding>,
    val warnings: List<DeployFinding>,
) {
    /** 기동 로그 한 덩어리 — 어떤 가드가 있고 어떤 상태인지(액추에이터 없이 보는 목록) */
    fun summary(): String = buildString {
        val envLabel = context.env?.name?.lowercase()
            ?: "unset (set ${DeployContext.ENV_VAR}=local|stage|prod to opt in to environment guards)"
        append("deploy guards: env=").append(envLabel).append(", ").append(guards.size).append(" guard(s)")
        guards.forEach { guard ->
            val p = problems.count { it.guard == guard }
            val w = warnings.count { it.guard == guard }
            append("\n  - ").append(guard).append(": ")
            append(if (p > 0) "$p problem(s)" else if (w > 0) "$w warning(s)" else "ok")
        }
    }

    companion object {
        fun evaluate(guards: List<DeployGuard>, context: DeployContext): DeployGuardReport = DeployGuardReport(
            context = context,
            guards = guards.map { it.name },
            problems = guards.flatMap { g -> g.problems(context).map { DeployFinding(g.name, it) } },
            warnings = guards.flatMap { g -> g.warnings(context).map { DeployFinding(g.name, it) } },
        )
    }
}
