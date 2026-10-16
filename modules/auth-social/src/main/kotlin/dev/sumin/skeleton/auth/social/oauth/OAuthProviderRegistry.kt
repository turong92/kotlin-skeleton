package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.auth.social.config.AuthSocialProperties
import dev.sumin.skeleton.common.project.Project
import dev.sumin.skeleton.common.project.ProjectRegistry
import dev.sumin.skeleton.common.project.ProjectScoped

/**
 * 프로젝트 모드에서 제공자 모듈이 내는 것 — 그 프로젝트의 설정(`skeleton.projects.<id>.config`)으로 만든 제공자들. 켜지지 않았으면 빈 목록.
 * 프로젝트마다 한 번 만들어 [OAuthProviderRegistry] 가 보관한다 (OIDC 의 discovery · JWKS 보관도 그 인스턴스 안이라 프로젝트끼리 섞이지 않는다).
 */
fun interface OAuthProviderSource {
    fun providersFor(project: Project): List<OAuthProvider>

    companion object {
        /**
         * 프로젝트의 소셜 설정 — 켜짐(`enabled`)과 `redirect-uri` 는 **프로젝트 자기 값만** 읽는다(공통 값으로 내려가지 않는다):
         * 한 서비스 시절의 공통 `enabled` · A 의 redirect URI 가 남아 있어도 B 에 제공자가 켜지거나 B 의 `/auth/methods` 가 A 의 주소를 내주지 않는다.
         * client id · secret 등 나머지는 공통 위에 덮는다(켜는 프로젝트가 id 를 빠뜨리면 제공자가 기동에서 거절한다).
         */
        fun projectProperties(projects: ProjectRegistry, project: Project): AuthSocialProperties {
            val merged = projects.bind(project, "skeleton.auth-social", AuthSocialProperties::class.java)
            val own = projects.bindOwn(project, "skeleton.auth-social", AuthSocialProperties::class.java).providers
            return merged.copy(providers = merged.providers.mapValues { (id, p) -> p.copy(enabled = own[id]?.enabled ?: false, redirectUri = own[id]?.redirectUri) })
        }

        /**
         * `skeleton.auth-social.providers.<id>` 를 쓰는 제공자(Google · Kakao · Naver)용: 그 프로젝트의 값(공통 위에 덮어)이 `enabled=true` 일 때만 [create] 로 만든다.
         * 한 서비스에서 빈 조건이 보던 `enabled` 와 같은 스위치다.
         */
        fun ofProvidersKey(projects: ProjectRegistry, id: String, create: (AuthSocialProperties.Provider) -> OAuthProvider): OAuthProviderSource =
            OAuthProviderSource { project ->
                projectProperties(projects, project).providers[id]
                    ?.takeIf { it.enabled }
                    ?.let { listOf(create(it)) }
                    ?: emptyList()
            }
    }
}

/**
 * 제공자 찾기. 목록이 비어 있는 앱(한 서비스)은 빈으로 만든 [providers] 와 공통 [properties] 를 그대로 쓴다.
 * 프로젝트 모드([projects] 가 있을 때)는 프로젝트마다 `providers`(앱이 직접 둔 빈, 공유) + [sources] 가 만든 제공자로 한 벌, 켜짐 · 설정은 그 프로젝트의 것이다 —
 * A 에서 켠 제공자는 B 의 목록에 나오지 않는다. 시작 때 서비스 중인 프로젝트마다 만들어 보며(잘못된 설정은 기동 실패, 메시지에 프로젝트 id) 요청 때는 지금 프로젝트의 것을 쓴다.
 */
class OAuthProviderRegistry(
    providers: List<OAuthProvider>,
    properties: AuthSocialProperties,
    projects: ProjectRegistry? = null,
    sources: List<OAuthProviderSource> = emptyList(),
) {
    private class View(val providers: Map<String, OAuthProvider>, val properties: AuthSocialProperties)

    private val shared = View(providers.associateByUniqueProviderId(), properties)

    private val perProject: ProjectScoped<View>? = projects?.let { registry ->
        val scoped = ProjectScoped { project ->
            val all = providers + sources.flatMap { it.providersFor(project) }
            View(all.associateByUniqueProviderId(), OAuthProviderSource.projectProperties(registry, project))
        }
        registry.active.forEach { project ->
            try {
                scoped.of(project)
            } catch (ex: RuntimeException) {
                throw IllegalStateException("Social login of project '${project.id}' cannot start: ${ex.message}", ex)
            }
        }
        scoped
    }

    private val view: View get() = perProject?.get() ?: shared

    private val activeProjects = projects?.active.orEmpty()

    /** 앱이 아는 제공자 id 전체 — 프로젝트 모드에서는 서비스 중인 프로젝트들의 합집합. 시작 때 한 번 모으는 로그인 수단 표처럼 **프로젝트 문맥 없이** 쓰는 곳용이다 (켜졌는지는 [findEnabled] 가 프로젝트마다 본다) */
    fun knownProviderIds(): Set<String> =
        if (perProject == null) shared.providers.keys else activeProjects.flatMapTo(linkedSetOf()) { perProject.of(it).providers.keys }

    /** 지금 프로젝트(프로젝트 모드가 아니면 앱 전체)의 제공자 — 켜졌는지는 보지 않는다 */
    fun providers(): List<OAuthProvider> = view.providers.values.toList()

    /** 지금 프로젝트의 `skeleton.auth-social.providers.<id>` 값 */
    fun settings(providerId: String): AuthSocialProperties.Provider? = view.properties.providers[providerId.normalizeProviderId()]

    fun findEnabled(providerId: String): OAuthProvider? {
        val normalized = providerId.normalizeProviderId()
        val current = view
        val provider = current.providers[normalized] ?: return null
        if (provider.autoEnabled || current.properties.providers[normalized]?.enabled == true) return provider
        return null
    }

    private fun String.normalizeProviderId(): String = trim().lowercase()

    private fun List<OAuthProvider>.associateByUniqueProviderId(): Map<String, OAuthProvider> {
        val grouped = groupBy { it.providerId.normalizeProviderId() }
        val duplicate = grouped.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) {
            "Duplicate OAuth provider id '${duplicate!!.key}'"
        }
        return grouped.mapValues { it.value.single() }
    }
}
