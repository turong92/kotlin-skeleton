package dev.sumin.skeleton.common.http

import org.springframework.http.HttpHeaders
import reactor.core.publisher.Mono

data class ExternalHttpResponse<T : Any>(
    val statusCode: Int,
    val headers: HttpHeaders,
    val body: T,
)

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

    fun <T : Any> postForm(
        clientName: String,
        path: String,
        form: Map<String, String>,
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
}

fun interface ExternalHttpClientCustomizer {
    fun customize(clientName: String, builder: org.springframework.web.reactive.function.client.WebClient.Builder)
}
