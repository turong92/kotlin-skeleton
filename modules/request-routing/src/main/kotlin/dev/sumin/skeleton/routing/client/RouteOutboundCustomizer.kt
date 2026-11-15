package dev.sumin.skeleton.routing.client

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.http.ExternalHttpClientCustomizer
import dev.sumin.skeleton.common.routing.Route
import dev.sumin.skeleton.common.routing.RouteBaggage
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.routing.LocalRouteWindow
import dev.sumin.skeleton.routing.RouteTarget
import dev.sumin.skeleton.routing.RouteTargets
import dev.sumin.skeleton.routing.RoutingErrorCode
import dev.sumin.skeleton.routing.isOpen
import org.slf4j.LoggerFactory
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono

/**
 * `services` 에 적힌 클라이언트에만 필터 하나를 단다 — 결제사 같은 외부 클라이언트에는 표시가 나가지 않는다.
 * 요청 MDC 의 표시를 `baggage` 로 싣고(이미 있는 baggage 는 `route` 멤버만 갈아 끼운다), 표시에 이 서비스 이름이 있고 허용 목록에 있으면
 * 주소의 스킴 · 호스트 · 포트만 대상으로 바꾼다(경로 · 쿼리 그대로). 로컬 대상은 호출마다 창을 다시 본다 — 닫혔으면 원래 서버로 몰래 가지 않고 실패한다.
 */
class RouteOutboundCustomizer(
    private val targets: RouteTargets,
    private val window: LocalRouteWindow,
    private val time: TimeProvider,
) : ExternalHttpClientCustomizer {
    private val log = LoggerFactory.getLogger(RouteOutboundCustomizer::class.java)

    override fun customize(clientName: String, builder: WebClient.Builder) {
        if (clientName in targets.propagatedServices) {
            builder.filter(ExchangeFilterFunction.ofRequestProcessor { request -> route(clientName, request) })
        }
    }

    private fun route(service: String, request: ClientRequest): Mono<ClientRequest> {
        val route = RouteBaggage.current() ?: return Mono.just(request)
        val target = route.targetOf(service)?.let { targets.find(service, it) }
        if (target?.local == true) {
            if (!window.isOpen(time.now())) return Mono.error(localClosed(service))
            log.warn("routing: local target used service={} target={}", service, target.name)
        }
        return Mono.just(rewrite(request, route, target))
    }

    private fun rewrite(request: ClientRequest, route: Route, target: RouteTarget?): ClientRequest {
        val builder = ClientRequest.from(request).headers { headers ->
            headers.set(RouteBaggage.HEADER, RouteBaggage.withRoute(headers.getFirst(RouteBaggage.HEADER), route))
            target?.headers?.forEach { (name, value) -> headers.set(name, value) }
        }
        if (target != null) {
            builder.url(
                UriComponentsBuilder.fromUri(request.url())
                    .scheme(target.origin.scheme)
                    .host(target.origin.host)
                    .port(target.origin.port)
                    .build(true)
                    .toUri(),
            )
        }
        return builder.build()
    }

    private fun localClosed(service: String) =
        ApplicationException("The local route window for service '$service' is closed.", RoutingErrorCode.LOCAL_CLOSED)
}
