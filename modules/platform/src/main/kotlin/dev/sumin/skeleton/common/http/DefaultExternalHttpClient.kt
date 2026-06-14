package dev.sumin.skeleton.common.http

import dev.sumin.skeleton.common.logging.SensitiveValueRedactor
import io.netty.channel.ChannelOption
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.UriBuilder
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient

class DefaultExternalHttpClient(
    private val webClientBuilder: WebClient.Builder,
    private val properties: OutboundHttpProperties,
    private val defaultErrorMapper: ExternalHttpErrorMapper,
    private val customizers: List<ExternalHttpClientCustomizer>,
    private val traceExtractor: ExternalHttpTraceExtractor = DefaultExternalHttpTraceExtractor(),
    redactor: SensitiveValueRedactor = SensitiveValueRedactor(),
) : ExternalHttpClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val filters = ExternalHttpFilters(properties, log, redactor)
    private val clients = ConcurrentHashMap<String, WebClient>()

    override fun <T : Any> get(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        getResponse(clientName, path, responseType, customize).map { it.body }

    override fun <T : Any> getResponse(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> =
        execute(HttpMethod.GET, clientName, path, body = null, responseType, customize)

    override fun <T : Any> post(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        postResponse(clientName, path, body, responseType, customize).map { it.body }

    override fun <T : Any> postResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> =
        execute(HttpMethod.POST, clientName, path, body, responseType, customize)

    override fun <T : Any> postForm(
        clientName: String,
        path: String,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        postFormResponse(clientName, path, form, responseType, customize).map { it.body }

    override fun <T : Any> postFormResponse(
        clientName: String,
        path: String,
        form: Map<String, String>,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> {
        val formBody = LinkedMultiValueMap<String, String>().apply {
            form.forEach { (name, value) -> add(name, value) }
        }
        return execute(HttpMethod.POST, clientName, path, FormBody(formBody), responseType) {
            header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_FORM_URLENCODED_VALUE)
            customize()
        }
    }

    override fun <T : Any> put(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        putResponse(clientName, path, body, responseType, customize).map { it.body }

    override fun <T : Any> putResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> =
        execute(HttpMethod.PUT, clientName, path, body, responseType, customize)

    override fun <T : Any> patch(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        patchResponse(clientName, path, body, responseType, customize).map { it.body }

    override fun <T : Any> patchResponse(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> =
        execute(HttpMethod.PATCH, clientName, path, body, responseType, customize)

    override fun <T : Any> delete(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        deleteResponse(clientName, path, responseType, customize).map { it.body }

    override fun <T : Any> deleteResponse(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> =
        execute(HttpMethod.DELETE, clientName, path, body = null, responseType, customize)

    private fun <T : Any> execute(
        method: HttpMethod,
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<ExternalHttpResponse<T>> {
        val requestSpec = ExternalHttpRequestSpec().apply(customize)
        val clientProperties = properties.clients[clientName] ?: OutboundHttpProperties.Client()
        val timeout = requestSpec.timeout ?: clientProperties.responseTimeout ?: properties.defaultResponseTimeout
        val uri = externalHttpExceptionUri(clientProperties, path, requestSpec)
        val startedAt = System.nanoTime()

        return prepareRequest(client(clientName), clientProperties, method, path, body, requestSpec)
            .exchangeToMono { response ->
                if (response.statusCode().isError) {
                    val upstreamHeaders = HttpHeaders.readOnlyHttpHeaders(response.headers().asHttpHeaders())
                    val upstreamTrace = traceExtractor.extract(upstreamHeaders, traceHeaderNames(clientProperties, requestSpec))
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .flatMap { responseBody ->
                            Mono.error<ExternalHttpResponse<T>>(
                                (requestSpec.errorMapper ?: defaultErrorMapper).map(
                                    ExternalHttpErrorContext(
                                        clientName = clientName,
                                        method = method.name(),
                                        uri = uri,
                                        upstreamStatus = response.statusCode().value(),
                                        upstreamBody = responseBody,
                                        upstreamHeaders = upstreamHeaders,
                                        trace = upstreamTrace,
                                    ),
                                ),
                        )
                    }
                } else {
                    val upstreamHeaders = HttpHeaders.readOnlyHttpHeaders(response.headers().asHttpHeaders())
                    val upstreamTrace = traceExtractor.extract(upstreamHeaders, traceHeaderNames(clientProperties, requestSpec))
                    response.bodyToMono(responseType)
                        .map { body ->
                            ExternalHttpResponse(
                                statusCode = response.statusCode().value(),
                                headers = upstreamHeaders,
                                body = body,
                                trace = upstreamTrace,
                            )
                        }
                }
            }
            .timeout(timeout)
            .doOnEach {
                if (it.isOnComplete || it.hasValue() || it.hasError()) {
                    filters.logCompletion(clientName, method, path, startedAt, requestSpec)
                }
            }
            .onErrorMap { error -> mapExternalHttpTransportError(error, clientName, method, uri) }
    }

    private fun prepareRequest(
        client: WebClient,
        clientProperties: OutboundHttpProperties.Client,
        method: HttpMethod,
        path: String,
        body: Any?,
        requestSpec: ExternalHttpRequestSpec,
    ): WebClient.RequestHeadersSpec<*> {
        val targetUri = absoluteUriOrNull(clientProperties, path, requestSpec)
        val request = when (method) {
            HttpMethod.GET -> if (targetUri != null) {
                client.get().uri(targetUri)
            } else {
                client.get().uri { uriBuilder -> relativeUri(uriBuilder, path, requestSpec) }
            }
            HttpMethod.DELETE -> if (targetUri != null) {
                client.delete().uri(targetUri)
            } else {
                client.delete().uri { uriBuilder -> relativeUri(uriBuilder, path, requestSpec) }
            }
            else -> {
                val bodySpec = if (targetUri != null) {
                    client.method(method).uri(targetUri)
                } else {
                    client.method(method).uri { uriBuilder -> relativeUri(uriBuilder, path, requestSpec) }
                }
                if (body is FormBody) {
                    bodySpec.body(BodyInserters.fromFormData(body.values))
                } else {
                    bodySpec.bodyValue(body ?: "")
                }
            }
        }

        requestSpec.headers.forEach { name, values -> request.header(name, *values.toTypedArray()) }
        requestSpec.cookies.forEach { name, values -> values.forEach { request.cookie(name, it) } }
        requestSpec.attributes.forEach { name, value -> request.attribute(name, value) }

        return request
    }

    private fun absoluteUriOrNull(
        clientProperties: OutboundHttpProperties.Client,
        path: String,
        requestSpec: ExternalHttpRequestSpec,
    ): URI? {
        val baseUrl = requestSpec.baseUrl ?: clientProperties.baseUrl.takeIf { it.isNotBlank() }
        val uriSource = when {
            path.isAbsoluteUrl() -> path
            baseUrl != null -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
            else -> return null
        }
        val builder = UriComponentsBuilder.fromUriString(uriSource)
        requestSpec.queryParams.forEach { name, values ->
            values.forEach { builder.queryParam(name, it) }
        }
        return builder.buildAndExpand(requestSpec.uriVariables).toUri()
    }

    private fun relativeUri(
        uriBuilder: UriBuilder,
        path: String,
        requestSpec: ExternalHttpRequestSpec,
    ): URI {
        uriBuilder.path(path)
        requestSpec.queryParams.forEach { name, values ->
            values.forEach { uriBuilder.queryParam(name, it) }
        }
        return uriBuilder.build(requestSpec.uriVariables)
    }

    private fun client(clientName: String): WebClient =
        clients.computeIfAbsent(clientName) {
            val clientProperties = properties.clients[clientName] ?: OutboundHttpProperties.Client()
            val connectTimeout = clientProperties.connectTimeout ?: properties.defaultConnectTimeout
            val responseTimeout = clientProperties.responseTimeout ?: properties.defaultResponseTimeout
            val httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout.toMillis().toInt())
                .responseTimeout(responseTimeout)
            val builder = webClientBuilder.clone()
                .clientConnector(ReactorClientHttpConnector(httpClient))
                .filter(filters.tracePropagationFilter())
                .filter(filters.loggingFilter())
                .codecs { codecs ->
                    codecs.defaultCodecs().maxInMemorySize(properties.maxInMemorySize.toBytes().toInt())
                }
            if (clientProperties.baseUrl.isNotBlank()) {
                builder.baseUrl(clientProperties.baseUrl)
            }
            clientProperties.defaultHeaders.forEach { (name, value) ->
                builder.defaultHeader(name, value)
            }
            customizers.forEach { customizer -> customizer.customize(clientName, builder) }
            builder.build()
        }

    private fun traceHeaderNames(
        clientProperties: OutboundHttpProperties.Client,
        requestSpec: ExternalHttpRequestSpec,
    ): List<String> =
        (requestSpec.vendorTraceHeaders + clientProperties.vendorTraceHeaders + properties.vendorTraceHeaders)
            .distinctBy { it.lowercase() }

    private fun String.isAbsoluteUrl(): Boolean =
        startsWith("http://") || startsWith("https://")

    private data class FormBody(
        val values: LinkedMultiValueMap<String, String>,
    )
}
