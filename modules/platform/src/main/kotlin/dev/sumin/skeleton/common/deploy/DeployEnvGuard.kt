package dev.sumin.skeleton.common.deploy

/**
 * 스위치 자체의 가드. `skeleton.deploy.require-env=true` 인데 `skeleton.env` 가 비면 기동 실패(기본은 꺼져 있다 — 스위치는 옵트인).
 * 스위치와 스프링 프로필 `prod` 가 어긋나면 **경고만** 한다: 어느 쪽도 다른 쪽을 켜지 않는다(스프링 기본값을 건드리지 않는다).
 */
class DeployEnvGuard(private val requireEnv: Boolean) : DeployGuard {
    override val name: String = "deploy-env"

    override fun problems(context: DeployContext): List<String> =
        if (requireEnv && context.env == null) {
            listOf("${DeployContext.ENV_VAR} (${DeployContext.PROPERTY}) is required (skeleton.deploy.require-env=true): set it to local, stage or prod")
        } else {
            emptyList()
        }

    override fun warnings(context: DeployContext): List<String> {
        val prodEnv = context.env == DeployEnv.PROD
        val prodProfile = "prod" in context.activeProfiles
        return when {
            prodEnv && !prodProfile ->
                listOf("${DeployContext.PROPERTY}=prod but the Spring profile prod is not active — profile-based guards and yml use their own profile lists")
            !prodEnv && prodProfile && context.env != null ->
                listOf("the Spring profile prod is active but ${DeployContext.PROPERTY} is not prod — environment guards for prod are off")
            else -> emptyList()
        }
    }
}
