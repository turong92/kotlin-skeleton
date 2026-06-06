package dev.sumin.skeleton.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import kotlin.math.ceil
import org.slf4j.MDC

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResponseMeta(
    val traceId: String? = null,
    val spanId: String? = null,
    val timestamp: String = Instant.now().toString(),
) {
    companion object {
        fun current(): ResponseMeta =
            ResponseMeta(
                traceId = MDC.get(TraceIdFilter.MDC_KEY),
                spanId = MDC.get(TraceIdFilter.MDC_SPAN_ID_KEY),
            )
    }
}

data class ApiValueResponse<T>(
    val value: T,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class ApiListResponse<T>(
    val values: List<T>,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class ApiPageResponse<T>(
    val values: List<T>,
    val pagination: PaginationMeta,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class PaginationMeta(
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
) {
    companion object {
        fun of(page: Int, size: Int, totalElements: Long): PaginationMeta {
            require(page >= 0) { "page must be greater than or equal to 0" }
            require(size > 0) { "size must be greater than 0" }
            require(totalElements >= 0) { "totalElements must be greater than or equal to 0" }

            val totalPages = ceil(totalElements.toDouble() / size.toDouble()).toInt()
            return PaginationMeta(
                page = page,
                size = size,
                totalElements = totalElements,
                totalPages = totalPages,
                hasNext = page + 1 < totalPages,
                hasPrevious = page > 0 && totalPages > 0,
            )
        }
    }
}

object ApiResponse {
    fun <T> value(value: T, meta: ResponseMeta = ResponseMeta.current()): ApiValueResponse<T> =
        ApiValueResponse(value = value, meta = meta)

    fun <T> list(values: List<T>, meta: ResponseMeta = ResponseMeta.current()): ApiListResponse<T> =
        ApiListResponse(values = values, meta = meta)

    fun <T> page(
        values: List<T>,
        pagination: PaginationMeta,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ApiPageResponse<T> =
        ApiPageResponse(values = values, pagination = pagination, meta = meta)
}
