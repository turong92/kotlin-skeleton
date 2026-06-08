package dev.sumin.skeleton.common.http

import java.net.URI

data class ExternalHttpErrorContext(
    val clientName: String,
    val method: String,
    val uri: URI,
    val upstreamStatus: Int,
    val upstreamBody: String,
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
        )

    private companion object {
        const val MAX_BODY_LENGTH = 2_048
    }
}
