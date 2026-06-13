package dev.sumin.skeleton.common.http

import java.net.URI
import org.springframework.http.HttpHeaders

data class ExternalHttpErrorContext(
    val clientName: String,
    val method: String,
    val uri: URI,
    val upstreamStatus: Int,
    val upstreamBody: String,
    val upstreamHeaders: HttpHeaders = HttpHeaders(),
    val trace: ExternalHttpTrace = ExternalHttpTrace.NONE,
)

fun interface ExternalHttpErrorMapper {
    fun map(context: ExternalHttpErrorContext): ExternalHttpException
}

class DefaultExternalHttpErrorMapper : ExternalHttpErrorMapper {
    override fun map(context: ExternalHttpErrorContext): ExternalHttpException =
        ExternalHttpStatusException(
            clientName = context.clientName,
            method = context.method,
            uri = context.uri,
            upstreamStatus = context.upstreamStatus,
            upstreamBody = context.upstreamBody.take(MAX_BODY_LENGTH),
            providerTraceId = context.trace.traceId,
        )

    private companion object {
        const val MAX_BODY_LENGTH = 2_048
    }
}
