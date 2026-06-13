package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import java.net.URI
import org.springframework.http.HttpHeaders
import org.springframework.util.MultiValueMap

@Suppress("UNCHECKED_CAST")
internal fun ExternalHttpRequestSpec.headersForTest(): Map<String, List<String>> =
    (reflectedField("headers") as MultiValueMap<String, String>)
        .mapValues { it.value.toList() }

@Suppress("UNCHECKED_CAST")
internal fun ExternalHttpRequestSpec.uriVariablesForTest(): Map<String, Any> =
    reflectedField("uriVariables") as Map<String, Any>

internal fun providerErrorContext(
    clientName: String,
    upstreamStatus: Int,
    body: String,
    headers: HttpHeaders = HttpHeaders(),
): ExternalHttpErrorContext =
    ExternalHttpErrorContext(
        clientName = clientName,
        method = "POST",
        uri = URI.create("https://provider.example.test/payments"),
        upstreamStatus = upstreamStatus,
        upstreamBody = body,
        upstreamHeaders = headers,
    )

private fun ExternalHttpRequestSpec.reflectedField(name: String): Any {
    val field = javaClass.getDeclaredField(name)
    field.isAccessible = true
    return field.get(this)
}
