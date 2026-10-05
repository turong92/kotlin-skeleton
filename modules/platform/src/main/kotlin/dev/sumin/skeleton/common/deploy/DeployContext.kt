package dev.sumin.skeleton.common.deploy

import org.springframework.core.env.Environment

/** 배포 환경 하나 — `skeleton.env`(환경변수 `SKELETON_ENV`) 값. */
enum class DeployEnv {
    LOCAL,
    STAGE,
    PROD,
    ;

    companion object {
        fun parse(raw: String): DeployEnv? = entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/**
 * 가드가 지금 환경을 보는 창. [env] 는 `skeleton.env` — **정하지 않으면 null** 이고 그러면 이 스위치는 아무것도 보호하지 않는다
 * (스프링 프로필 기본값을 건드리지 않는다. 프로필로 보호하던 가드는 자기 프로필 목록으로 그대로 동작한다).
 */
data class DeployContext(
    val env: DeployEnv?,
    val activeProfiles: Set<String>,
) {
    /** `skeleton.env` 가 stage 또는 prod — 새 가드는 이것만 보면 된다 */
    val protectedEnv: Boolean get() = env == DeployEnv.STAGE || env == DeployEnv.PROD

    /** 스위치가 보호 환경이거나, 가드가 자기 프로필 목록으로 보호하는 프로필이 켜져 있다 */
    fun protectedBy(profiles: Collection<String>): Boolean = protectedBecause(profiles).isNotEmpty()

    /** 보호하는 이유: 켜진 보호 프로필(정렬)과, 스위치가 보호 환경이면 `skeleton.env=<값>` */
    fun protectedBecause(profiles: Collection<String>): List<String> =
        activeProfiles.filter { it in profiles }.sorted() +
            (if (protectedEnv) listOf("$PROPERTY=${env!!.name.lowercase()}") else emptyList())

    companion object {
        const val PROPERTY = "skeleton.env"
        const val ENV_VAR = "SKELETON_ENV"

        /** 비었거나 없으면 환경 없음. 모르는 값은 기동 실패 — 오타가 가드를 조용히 끄지 않게 한다(값은 메시지에 싣지 않는다) */
        fun from(environment: Environment): DeployContext {
            val raw = environment.getProperty(PROPERTY)?.trim().orEmpty()
            val env = if (raw.isEmpty()) {
                null
            } else {
                DeployEnv.parse(raw) ?: throw DeployGuardViolationException(
                    listOf(DeployFinding("deploy-env", "$ENV_VAR ($PROPERTY) must be one of local | stage | prod")),
                )
            }
            return DeployContext(env, environment.activeProfiles.toSet())
        }
    }
}
