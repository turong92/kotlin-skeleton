package dev.sumin.skeleton.common.http

import io.netty.channel.ChannelOption
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.http.HttpMethod
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient

class DefaultExternalHttpClient(
    private val webClientBuilder: WebClient.Builder,
    private val properties: OutboundHttpProperties,
    private val defaultErrorMapper: ExternalHttpErrorMapper,
    private val customizers: List<ExternalHttpClientCustomizer>,
) : ExternalHttpClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val filters = ExternalHttpFilters(properties, log)
    private val clients = ConcurrentHashMap<String, WebClient>()

    override fun <T : Any> get(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        execute(HttpMethod.GET, clientName, path, body = null, responseType, customize)

    override fun <T : Any> post(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        execute(HttpMethod.POST, clientName, path, body, responseType, customize)

    override fun <T : Any> put(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        execute(HttpMethod.PUT, clientName, path, body, responseType, customize)

    override fun <T : Any> patch(
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        execute(HttpMethod.PATCH, clientName, path, body, responseType, customize)

    override fun <T : Any> delete(
        clientName: String,
        path: String,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> =
        execute(HttpMethod.DELETE, clientName, path, body = null, responseType, customize)

    private fun <T : Any> execute(
        method: HttpMethod,
        clientName: String,
        path: String,
        body: Any?,
        responseType: Class<T>,
        customize: ExternalHttpRequestSpec.() -> Unit,
    ): Mono<T> {
        val requestSpec = ExternalHttpRequestSpec().apply(customize)
        val clientProperties = properties.clients[clientName] ?: OutboundHttpProperties.Client()
        val timeout = requestSpec.timeout ?: clientProperties.responseTimeout ?: properties.defaultResponseTimeout
        val uri = externalHttpExceptionUri(clientProperties, path, requestSpec)
        val startedAt = System.nanoTime()

        return prepareRequest(client(clientName), method, path, body, requestSpec)
            .exchangeToMono { response ->
                if (response.statusCode().isError) {
                    response.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .flatMap { responseBody ->
                            Mono.error<T>(
                                (requestSpec.errorMapper ?: defaultErrorMapper).map(
                                    ExternalHttpErrorContext(
                                        clientName = clientName,
                                        method = method.name(),
                                        uri = uri,
                                        upstreamStatus = response.statusCode().value(),
                                        upstreamBody = responseBody,
                                    ),
                                ),
                            )
                        }
                } else {
                    response.bodyToMono(responseType)
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
        method: HttpMethod,
        path: String,
        body: Any?,
        requestSpec: ExternalHttpRequestSpec,
    ): WebClient.RequestHeadersSpec<*> {
        val request = when (method) {
            HttpMethod.GET -> client.get().uri { uriBuilder ->
                uriBuilder.path(path)
                requestSpec.queryParams.forEach { name, values -> values.forEach { uriBuilder.queryParam(name, it) } }
                uriBuilder.build(requestSpec.uriVariables)
            }
            HttpMethod.DELETE -> client.delete().uri { uriBuilder ->
                uriBuilder.path(path)
                requestSpec.queryParams.forEach { name, values -> values.forEach { uriBuilder.queryParam(name, it) } }
                uriBuilder.build(requestSpec.uriVariables)
            }
            else -> client.method(method).uri { uriBuilder ->
                uriBuilder.path(path)
                requestSpec.queryParams.forEach { name, values -> values.forEach { uriBuilder.queryParam(name, it) } }
                uriBuilder.build(requestSpec.uriVariables)
            }.bodyValue(body ?: "")
        }

        requestSpec.headers.forEach { name, values -> request.header(name, *values.toTypedArray()) }
        requestSpec.cookies.forEach { name, values -> values.forEach { request.cookie(name, it) } }
        requestSpec.attributes.forEach { name, value -> request.attribute(name, value) }

        return request
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
}
