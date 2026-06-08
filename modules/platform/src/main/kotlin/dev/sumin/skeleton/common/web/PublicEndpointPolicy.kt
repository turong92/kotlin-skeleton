package dev.sumin.skeleton.common.web

import org.springframework.http.HttpMethod

data class PublicEndpoint(
    val method: HttpMethod?,
    val pattern: String,
) {
    companion object {
        fun any(pattern: String): PublicEndpoint =
            PublicEndpoint(method = null, pattern = pattern)

        fun method(method: String, pattern: String): PublicEndpoint =
            PublicEndpoint(method = HttpMethod.valueOf(method.uppercase()), pattern = pattern)
    }
}

fun interface PublicEndpointContributor {
    fun contribute(registry: MutablePublicEndpointRegistry)
}

class MutablePublicEndpointRegistry internal constructor(
    endpoints: List<PublicEndpoint> = emptyList(),
) {
    private val mutableEndpoints = endpoints.toMutableList()

    fun add(pattern: String) {
        mutableEndpoints += PublicEndpoint.any(pattern)
    }

    fun add(method: String, pattern: String) {
        mutableEndpoints += PublicEndpoint.method(method, pattern)
    }

    fun toRegistry(): PublicEndpointRegistry =
        PublicEndpointRegistry(mutableEndpoints.distinct())
}

class PublicEndpointRegistry(
    val endpoints: List<PublicEndpoint>,
) {
    val pathWidePatterns: Array<String> =
        endpoints.filter { it.method == null }
            .map { it.pattern }
            .toTypedArray()

    val methodSpecificEndpoints: List<PublicEndpoint> =
        endpoints.filter { it.method != null }

    companion object {
        fun default(): PublicEndpointRegistry =
            from(properties = WebProperties(), contributors = emptyList())

        fun from(
            properties: WebProperties,
            contributors: Iterable<PublicEndpointContributor>,
        ): PublicEndpointRegistry {
            val mutable = MutablePublicEndpointRegistry(defaultEndpoints())
            properties.publicEndpoints.forEach { endpoint ->
                if (endpoint.method.isNullOrBlank()) {
                    mutable.add(endpoint.path)
                } else {
                    mutable.add(endpoint.method, endpoint.path)
                }
            }
            contributors.forEach { contributor -> contributor.contribute(mutable) }
            return mutable.toRegistry()
        }

        private fun defaultEndpoints(): List<PublicEndpoint> =
            listOf(
                PublicEndpoint.any("/health"),
                PublicEndpoint.any("/info"),
                PublicEndpoint.any("/api/v1/docs"),
                PublicEndpoint.any("/api/v1/docs/**"),
                PublicEndpoint.any("/api/v1/docs/ui"),
                PublicEndpoint.any("/api/v1/docs/ui/**"),
                PublicEndpoint.any("/swagger-ui/**"),
                PublicEndpoint.any("/v3/api-docs/**"),
            )
    }
}
