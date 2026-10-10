package dev.sumin.skeleton.payment.toss

import dev.sumin.skeleton.common.http.ExternalHttpErrorContext
import java.net.URI
import org.springframework.http.HttpHeaders

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
