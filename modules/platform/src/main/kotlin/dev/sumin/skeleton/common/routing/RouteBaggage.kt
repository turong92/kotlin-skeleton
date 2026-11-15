package dev.sumin.skeleton.common.routing

import org.slf4j.MDC
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class RouteEntry(val service: String, val target: String)

data class Route(val entries: List<RouteEntry>) {
    fun targetOf(service: String): String? = entries.firstOrNull { it.service == service }?.target

    fun format(): String = entries.joinToString("|") { "${it.service}:${it.target}" }
}

sealed interface RouteParse {
    data object Absent : RouteParse

    data class Valid(val route: Route) : RouteParse

    data object Malformed : RouteParse
}

/**
 * The `route` member of the W3C `baggage` header: `route=<service>:<target>|<service>:<target>`.
 * Only names travel, never addresses; the name-to-address allowlist is server configuration.
 */
object RouteBaggage {
    const val HEADER = "baggage"
    const val MEMBER = "route"
    const val MDC_KEY = "route"
    const val MAX_ENTRIES = 8
    const val MAX_LENGTH = 256

    private val NAME = Regex("^[a-z][a-z0-9-]{0,39}$")

    fun parseHeader(values: List<String>): RouteParse {
        val routeValues = values.asSequence()
            .flatMap { it.split(',').asSequence() }
            .map { it.substringBefore(';').trim() }
            .filter { it.substringBefore('=').trim() == MEMBER && it.contains('=') }
            .map { it.substringAfter('=').trim() }
            .toList()
        if (routeValues.isEmpty()) return RouteParse.Absent
        if (routeValues.size > 1) return RouteParse.Malformed
        val decoded = decode(routeValues.single()) ?: return RouteParse.Malformed
        return parseValue(decoded)?.let { RouteParse.Valid(it) } ?: RouteParse.Malformed
    }

    fun parseValue(value: String): Route? {
        if (value.isEmpty() || value.length > MAX_LENGTH) return null
        val pairs = value.split('|')
        if (pairs.size > MAX_ENTRIES) return null
        val entries = pairs.map { pair ->
            val parts = pair.split(':')
            if (parts.size != 2 || !NAME.matches(parts[0]) || !NAME.matches(parts[1])) return null
            RouteEntry(parts[0], parts[1])
        }
        if (entries.map { it.service }.toSet().size != entries.size) return null
        return Route(entries)
    }

    fun withRoute(existing: String?, route: Route): String {
        val others = existing.orEmpty().split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.substringBefore('=').trim() != MEMBER }
        return (others + "$MEMBER=${route.format()}").joinToString(",")
    }

    fun current(): Route? = MDC.get(MDC_KEY)?.let { parseValue(it) }

    fun restore(value: String?): AutoCloseable {
        if (value == null || parseValue(value) == null) return AutoCloseable { }
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, value)
        return AutoCloseable {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }

    private fun decode(raw: String): String? =
        try {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            null
        }
}
