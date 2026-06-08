package dev.sumin.skeleton.common.http

import java.net.URI
import java.util.concurrent.TimeoutException
import org.springframework.http.HttpMethod
import org.springframework.web.reactive.function.client.WebClientRequestException

internal fun externalHttpExceptionUri(
    clientProperties: OutboundHttpProperties.Client,
    path: String,
    requestSpec: ExternalHttpRequestSpec,
): URI {
    val expandedPath = requestSpec.uriVariables.entries.fold(path) { current, (name, value) ->
        current.replace("{$name}", value.toString())
    }
    val uri = when {
        expandedPath.startsWith("http://") || expandedPath.startsWith("https://") -> expandedPath
        requestSpec.baseUrl != null -> requestSpec.baseUrl!!.trimEnd('/') + "/" + expandedPath.trimStart('/')
        clientProperties.baseUrl.isNotBlank() -> clientProperties.baseUrl.trimEnd('/') + "/" + expandedPath.trimStart('/')
        else -> expandedPath
    }
    return URI.create(uri)
}

internal fun mapExternalHttpTransportError(
    error: Throwable,
    clientName: String,
    method: HttpMethod,
    uri: URI,
): Throwable =
    when (error) {
        is ExternalHttpException -> error
        is TimeoutException -> ExternalHttpTimeoutException(clientName, method.name(), uri, error)
        is WebClientRequestException -> when {
            error.hasTimeoutCause() -> ExternalHttpTimeoutException(clientName, method.name(), uri, error)
            else -> ExternalHttpNetworkException(clientName, method.name(), uri, error)
        }
        else -> error
    }

private fun Throwable.hasTimeoutCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is TimeoutException || current.javaClass.simpleName.contains("Timeout")) {
            return true
        }
        current = current.cause
    }
    return false
}
