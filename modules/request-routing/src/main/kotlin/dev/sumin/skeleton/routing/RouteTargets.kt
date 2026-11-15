package dev.sumin.skeleton.routing

import java.net.URI

data class RouteTarget(
    val service: String,
    val name: String,
    val origin: URI,
    val local: Boolean,
    val headers: Map<String, String>,
)

/**
 * 허용 목록 — 표시엔 이름만 오고, 이름 → 주소는 설정에서만 정한다(표시로 아무 주소나 부르지 못하게).
 * 만들 때 이름 모양과 `url`(http · https 원점만)을 검사하고, 어기면 기동이 실패한다 — 메시지엔 속성 이름만 싣는다(값은 싣지 않는다).
 */
class RouteTargets(properties: RequestRoutingProperties) {
    private val byService: Map<String, Map<String, RouteTarget>> =
        properties.services.mapValues { (service, config) ->
            requireName(service, "skeleton.routing.services.$service")
            config.targets.mapValues { (name, target) ->
                val prefix = "skeleton.routing.services.$service.targets.$name"
                requireName(name, prefix)
                RouteTarget(service, name, origin(target.url, "$prefix.url"), target.local, target.headers)
            }
        }

    val propagatedServices: Set<String> = byService.keys

    val hasLocalTargets: Boolean = byService.values.any { targets -> targets.values.any { it.local } }

    fun knowsService(service: String): Boolean = service in byService

    fun find(service: String, target: String): RouteTarget? = byService[service]?.get(target)

    private fun requireName(name: String, property: String) {
        check(NAME.matches(name)) { "$property: the key must match ${NAME.pattern}" }
    }

    private fun origin(url: String, property: String): URI {
        val uri = runCatching { URI(url) }.getOrNull()
        val valid = uri != null &&
            uri.scheme in setOf("http", "https") &&
            !uri.host.isNullOrEmpty() &&
            uri.userInfo == null &&
            uri.rawPath.orEmpty().let { it.isEmpty() || it == "/" } &&
            uri.rawQuery == null &&
            uri.rawFragment == null
        check(valid) { "$property: must be an http(s) origin (scheme, host, optional port) with no path, query or credentials" }
        return URI(uri!!.scheme, null, uri.host, uri.port, null, null, null)
    }

    private companion object {
        val NAME = Regex("^[a-z][a-z0-9-]{0,39}$")
    }
}
