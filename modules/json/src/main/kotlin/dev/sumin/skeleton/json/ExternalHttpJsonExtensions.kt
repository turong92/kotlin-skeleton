package dev.sumin.skeleton.json

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpEndpoint
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import dev.sumin.skeleton.common.http.ExternalHttpResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import reactor.core.publisher.Mono

fun ExternalHttpClient.getJsonResponse(
    endpoint: ExternalHttpEndpoint,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    getResponse(endpoint, JsonDocument::class.java, customize)

fun ExternalHttpClient.getJsonResponse(
    clientName: String,
    path: String,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    getResponse(clientName, path, JsonDocument::class.java, customize)

fun ExternalHttpClient.postJsonResponse(
    endpoint: ExternalHttpEndpoint,
    body: JsonDocument,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    postResponse(endpoint, body, JsonDocument::class.java) {
        jsonContentType()
        customize()
    }

fun ExternalHttpClient.postJsonResponse(
    clientName: String,
    path: String,
    body: JsonDocument,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    postResponse(clientName, path, body, JsonDocument::class.java) {
        jsonContentType()
        customize()
    }

fun ExternalHttpClient.putJsonResponse(
    endpoint: ExternalHttpEndpoint,
    body: JsonDocument,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    putResponse(endpoint, body, JsonDocument::class.java) {
        jsonContentType()
        customize()
    }

fun ExternalHttpClient.patchJsonResponse(
    endpoint: ExternalHttpEndpoint,
    body: JsonDocument,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    patchResponse(endpoint, body, JsonDocument::class.java) {
        jsonContentType()
        customize()
    }

fun ExternalHttpClient.deleteJsonResponse(
    endpoint: ExternalHttpEndpoint,
    customize: ExternalHttpRequestSpec.() -> Unit = {},
): Mono<ExternalHttpResponse<JsonDocument>> =
    deleteResponse(endpoint, JsonDocument::class.java, customize)

private fun ExternalHttpRequestSpec.jsonContentType() {
    header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
}
