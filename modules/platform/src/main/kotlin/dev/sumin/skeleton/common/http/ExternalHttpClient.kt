package dev.sumin.skeleton.common.http

import reactor.core.publisher.Mono

interface ExternalHttpClient {
    fun <T : Any> get(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> post(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> put(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> patch(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> delete(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> getBlocking(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): T = requireNotNull(get(clientName, path, responseType, customize).block())
}

fun interface ExternalHttpClientCustomizer {
    fun customize(clientName: String, builder: org.springframework.web.reactive.function.client.WebClient.Builder)
}
