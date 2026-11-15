package dev.sumin.skeleton.routing.web

import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.routing.Route
import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.common.routing.RouteParse
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.RequestRoutingProperties
import dev.sumin.skeleton.routing.RouteTargets
import dev.sumin.skeleton.routing.RoutingErrorCode
import dev.sumin.skeleton.routing.RoutingPermissions
import dev.sumin.skeleton.routing.isOpen
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.util.Collections
import org.slf4j.LoggerFactory
import org.springframework.util.ClassUtils
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 들어오는 요청의 `baggage` `route` 멤버를 믿고 · 검사하고 · MDC(`route`)에 둔다. 보안 체인 **뒤**에 선다.
 * 순서: 파싱 → 믿기 → 검사 → MDC → 체인.
 * - 믿기: `edge` 는 인증된 호출자가 `routing.use` 를 가질 때만, `internal` 은 늘(공개 주소 없는 서비스만 쓴다). 못 믿으면 **말없이 버린다**(모양이 틀려도 400 이 아니다).
 * - 검사: 모양 오류 → 400, 아는 서비스의 모르는 대상 → 400, 로컬 대상인데 창이 닫힘 → 403. 모르는 서비스는 뒤 서비스 몫이라 그대로 둔다.
 */
class RouteInboundFilter(
    private val properties: RequestRoutingProperties,
    private val targets: RouteTargets,
    private val window: LocalRouteWindow,
    private val access: OperatorAccess,
    private val time: TimeProvider,
    private val errors: RoutingErrorWriter,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RouteInboundFilter::class.java)
    private val securityPresent = ClassUtils.isPresent(SECURITY_CLASS, RouteInboundFilter::class.java.classLoader)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val parsed = RouteBaggage.parseHeader(Collections.list(request.getHeaders(RouteBaggage.HEADER)))
        if (parsed is RouteParse.Absent) return chain.doFilter(request, response)

        val actor = trustedActor() ?: return chain.doFilter(request, response)
        val route = (parsed as? RouteParse.Valid)?.route
            ?: return errors.write(response, RoutingErrorCode.MALFORMED, "The route baggage member is malformed.")

        val problem = check(route)
        if (problem != null) return errors.write(response, problem.first, problem.second)
        warnLocalUse(actor, route)

        RouteBaggage.restore(route.format()).use { chain.doFilter(request, response) }
    }

    /** 표시를 믿어도 되는 호출이면 행위자 표시(로그용), 아니면 null */
    private fun trustedActor(): String? {
        val caller = if (securityPresent) SecurityCaller.current() else null
        if (properties.inbound == RequestRoutingProperties.Inbound.INTERNAL) return caller?.accountId ?: INTERNAL_ACTOR
        if (caller == null) return null
        return caller.accountId.takeIf { access.has(caller, RoutingPermissions.USE) }
    }

    private fun check(route: Route): Pair<RoutingErrorCode, String>? {
        route.entries.filter { targets.knowsService(it.service) }.forEach { entry ->
            val target = targets.find(entry.service, entry.target)
                ?: return RoutingErrorCode.UNKNOWN_TARGET to "The route target is not in the allowlist for service '${entry.service}'."
            if (target.local && !window.isOpen(time.now())) {
                return RoutingErrorCode.LOCAL_CLOSED to "The local route window for service '${entry.service}' is closed."
            }
        }
        return null
    }

    private fun warnLocalUse(actor: String, route: Route) {
        route.entries
            .filter { targets.find(it.service, it.target)?.local == true }
            .forEach { log.warn("routing: local target used actor={} route={}:{}", actor, it.service, it.target) }
    }

    private companion object {
        const val INTERNAL_ACTOR = "internal"
        const val SECURITY_CLASS = "org.springframework.security.core.context.SecurityContextHolder"
    }
}
