package dev.sumin.skeleton.idempotency

import dev.sumin.skeleton.common.ApiError
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.TraceIdFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Clock
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import tools.jackson.databind.ObjectMapper

class IdempotencyHandlerInterceptor(
    private val properties: IdempotencyProperties,
    private val store: IdempotencyStore,
    private val scopeResolver: IdempotencyScopeResolver,
    private val objectMapper: ObjectMapper,
    private val clock: Clock = Clock.systemUTC(),
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val handlerMethod = handler as? HandlerMethod ?: return true
        if (handlerMethod.getMethodAnnotation(IdempotentOperation::class.java) == null) {
            return true
        }

        val rawKey = request.getHeader(IdempotencyHeaders.IDEMPOTENCY_KEY)?.trim()
        if (rawKey.isNullOrBlank()) {
            writeError(
                response = response,
                status = HttpStatus.BAD_REQUEST,
                title = "Missing Idempotency-Key",
                detail = "Idempotency-Key header is required for this operation.",
                rawKey = null,
            )
            return false
        }

        val scopedKey = "${scopeResolver.resolve(request)}:$rawKey"
        val idempotencyRequest = IdempotencyRequest(
            scopedKey = scopedKey,
            rawKey = rawKey,
            fingerprint = IdempotencyFingerprint.calculate(request, cachedBody(request)),
            expiresAt = clock.instant().plus(properties.ttl),
        )

        return when (val reservation = store.reserve(idempotencyRequest)) {
            is IdempotencyReservation.Started -> {
                request.setAttribute(IdempotencyAttributes.CONTEXT, IdempotencyContext(scopedKey = scopedKey))
                response.setHeader(IdempotencyHeaders.IDEMPOTENCY_KEY, rawKey)
                response.setHeader(IdempotencyHeaders.IDEMPOTENCY_REPLAYED, "false")
                true
            }

            is IdempotencyReservation.Replay -> {
                writeStoredResponse(response, rawKey, reservation.response)
                false
            }

            IdempotencyReservation.Conflict -> {
                writeError(
                    response = response,
                    status = HttpStatus.CONFLICT,
                    title = "Idempotency key conflict",
                    detail = "The same Idempotency-Key was used with a different request.",
                    rawKey = rawKey,
                )
                false
            }

            IdempotencyReservation.InProgress -> {
                writeError(
                    response = response,
                    status = HttpStatus.CONFLICT,
                    title = "Idempotency request in progress",
                    detail = "The same Idempotency-Key is already being processed.",
                    rawKey = rawKey,
                )
                false
            }
        }
    }

    private fun cachedBody(request: HttpServletRequest): ByteArray =
        request.getAttribute(IdempotencyAttributes.CACHED_BODY) as? ByteArray
            ?: (request as? CachedBodyHttpServletRequest)?.cachedBody
            ?: ByteArray(0)

    private fun writeStoredResponse(
        response: HttpServletResponse,
        rawKey: String,
        stored: StoredIdempotencyResponse,
    ) {
        response.status = stored.status
        stored.contentType?.let { response.contentType = it }
        stored.headers.forEach { (name, values) ->
            values.forEach { value -> response.addHeader(name, value) }
        }
        response.setHeader(IdempotencyHeaders.IDEMPOTENCY_KEY, rawKey)
        response.setHeader(IdempotencyHeaders.IDEMPOTENCY_REPLAYED, "true")
        response.outputStream.write(stored.body)
    }

    private fun writeError(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
        rawKey: String?,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        rawKey?.let { response.setHeader(IdempotencyHeaders.IDEMPOTENCY_KEY, it) }
        response.setHeader(IdempotencyHeaders.IDEMPOTENCY_REPLAYED, "false")
        objectMapper.writeValue(
            response.outputStream,
            ApiError(
                code = PlatformErrorCode.IDEMPOTENCY_ERROR.code,
                title = title,
                status = status.value(),
                detail = detail,
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            ),
        )
    }
}
