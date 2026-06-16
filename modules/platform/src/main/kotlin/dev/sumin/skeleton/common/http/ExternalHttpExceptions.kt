package dev.sumin.skeleton.common.http

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.PlatformErrorCode
import java.net.URI

open class ExternalHttpException(
    message: String,
    val clientName: String,
    val method: String,
    val uri: URI,
    val upstreamStatus: Int?,
    val retryable: Boolean,
    val providerTraceId: String? = null,
    errorCode: ErrorCode,
    detail: String? = message,
    data: Any? = null,
    cause: Throwable? = null,
) : ApplicationException(
    message = message,
    errorCode = errorCode,
    detail = detail,
    data = data,
    cause = cause,
)

class ExternalHttpStatusException(
    clientName: String,
    method: String,
    uri: URI,
    upstreamStatus: Int,
    val upstreamBody: String,
    providerTraceId: String? = null,
) : ExternalHttpException(
    message = "External service returned HTTP $upstreamStatus",
    clientName = clientName,
    method = method,
    uri = uri,
    upstreamStatus = upstreamStatus,
    retryable = upstreamStatus >= 500 || upstreamStatus == 429,
    providerTraceId = providerTraceId,
    errorCode = PlatformErrorCode.EXTERNAL_SERVICE_ERROR,
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
    errorCode = PlatformErrorCode.EXTERNAL_SERVICE_TIMEOUT,
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
    errorCode = PlatformErrorCode.EXTERNAL_SERVICE_ERROR,
    cause = cause,
)
