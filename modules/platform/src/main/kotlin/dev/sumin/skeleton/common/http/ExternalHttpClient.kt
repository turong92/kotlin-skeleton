package dev.sumin.skeleton.common.http

import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono

data class ExternalHttpEndpoint(
    val clientName: String,
    val path: String,
) {
    init {
        require(clientName.isNotBlank()) { "External HTTP client name must not be blank." }
        require(path.isNotBlank()) { "External HTTP path must not be blank." }
    }
}

data class ExternalHttpResponse<T : Any>(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: T,
    val trace: ExternalHttpTrace = ExternalHttpTrace.NONE,
)

interface ExternalHttpClient {
    fun <T : Any> get(
        endpoint: ExternalHttpEndpoint,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        get(endpoint.clientName, endpoint.path, responseType, customize)

    fun <T : Any> get(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> post(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        post(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> post(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> postForm(
        endpoint: ExternalHttpEndpoint,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        postForm(endpoint.clientName, endpoint.path, form, responseType, customize)

    fun <T : Any> postForm(
        clientName: String,
        path: String,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> put(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        put(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> put(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> patch(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        patch(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> patch(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> delete(
        endpoint: ExternalHttpEndpoint,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T> =
        delete(endpoint.clientName, endpoint.path, responseType, customize)

    fun <T : Any> delete(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<T>

    fun <T : Any> getResponse(
        endpoint: ExternalHttpEndpoint,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        getResponse(endpoint.clientName, endpoint.path, responseType, customize)

    fun <T : Any> getResponse(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        get(clientName, path, responseType, customize).map { body ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = body)
        }

    fun <T : Any> postResponse(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        postResponse(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> postResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        post(clientName, path, body, responseType, customize).map { responseBody ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = responseBody)
        }

    fun <T : Any> postFormResponse(
        endpoint: ExternalHttpEndpoint,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        postFormResponse(endpoint.clientName, endpoint.path, form, responseType, customize)

    fun <T : Any> postFormResponse(
        clientName: String,
        path: String,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        postForm(clientName, path, form, responseType, customize).map { body ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = body)
        }

    fun <T : Any> putResponse(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        putResponse(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> putResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        put(clientName, path, body, responseType, customize).map { responseBody ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = responseBody)
        }

    fun <T : Any> patchResponse(
        endpoint: ExternalHttpEndpoint,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        patchResponse(endpoint.clientName, endpoint.path, body, responseType, customize)

    fun <T : Any> patchResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        patch(clientName, path, body, responseType, customize).map { responseBody ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = responseBody)
        }

    fun <T : Any> deleteResponse(
        endpoint: ExternalHttpEndpoint,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        deleteResponse(endpoint.clientName, endpoint.path, responseType, customize)

    fun <T : Any> deleteResponse(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): Mono<ExternalHttpResponse<T>> =
        delete(clientName, path, responseType, customize).map { body ->
            ExternalHttpResponse(statusCode = 200, headers = HttpHeaders(), body = body)
        }

    fun <T : Any> getBlocking(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): T = requireNotNull(get(clientName, path, responseType, customize).block())

    fun <T : Any> getBlocking(
        endpoint: ExternalHttpEndpoint,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit = {},
    ): T = requireNotNull(get(endpoint, responseType, customize).block())
}

fun interface ExternalHttpClientCustomizer {
    fun customize(clientName: String, builder: org.springframework.web.reactive.function.client.WebClient.Builder)
}
