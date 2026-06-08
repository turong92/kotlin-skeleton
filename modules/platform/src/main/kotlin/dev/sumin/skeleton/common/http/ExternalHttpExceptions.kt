package dev.sumin.skeleton.common.http

import dev.sumin.skeleton.common.ApplicationException
import java.net.URI
import org.springframework.http.HttpStatus

open class ExternalHttpException(
    message: String,
    val clientName: String,
    val method: String,
    val uri: URI,
    val upstreamStatus: Int?,
    val retryable: Boolean,
    status: HttpStatus,
    title: String,
    cause: Throwable? = null,
) : ApplicationException(message = message, status = status, title = title, cause = cause)

class ExternalHttpStatusException(
    clientName: String,
    method: String,
    uri: URI,
    upstreamStatus: Int,
    val upstreamBody: String,
) : ExternalHttpException(
    message = "External service returned HTTP $upstreamStatus",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = upstreamStatus,
    retryable = upstreamStatus >= 500 || upstreamStatus == 429,
    status = HttpStatus.BAD_GATEWAY,
    title = "External service error",
)

class ExternalHttpTimeoutException(
    clientName: String,
    method: String,
    uri: URI,
    cause: Throwable? = null,
) : ExternalHttpException(
    message = "External service timed out",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = null,
    retryable = true,
    status = HttpStatus.GATEWAY_TIMEOUT,
    title = "External service timeout",
    cause = cause,
)

class ExternalHttpNetworkException(
    clientName: String,
    method: String,
    uri: URI,
    cause: Throwable,
) : ExternalHttpException(
    message = "External service network error",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = null,
    retryable = true,
    status = HttpStatus.BAD_GATEWAY,
    title = "External service network error",
    cause = cause,
)
