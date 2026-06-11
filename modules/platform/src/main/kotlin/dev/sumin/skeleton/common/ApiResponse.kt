package dev.sumin.skeleton.common

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.net.URI
import kotlin.math.ceil
import org.slf4j.MDC
import org.springframework.http.ResponseEntity

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

data class BasicResponse(
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class DataResponse<T>(
    val value: T,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class ListResponse<T>(
    val values: List<T>,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class PageResponse<T>(
    val values: List<T>,
    val pagination: PaginationMeta,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class CursorResponse<T>(
    val values: List<T>,
    val cursor: CursorMeta,
    val meta: ResponseMeta = ResponseMeta.current(),
)

data class CursorMeta(
    val nextCursor: String?,
    val hasNext: Boolean,
)

typealias ApiValueResponse<T> = DataResponse<T>
typealias ApiListResponse<T> = ListResponse<T>
typealias ApiPageResponse<T> = PageResponse<T>

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

object Response {
    fun ok(meta: ResponseMeta = ResponseMeta.current()): BasicResponse =
        BasicResponse(meta = meta)

    fun <T> ok(value: T, meta: ResponseMeta = ResponseMeta.current()): DataResponse<T> =
        DataResponse(value = value, meta = meta)

    fun <T> ok(values: List<T>, meta: ResponseMeta = ResponseMeta.current()): ListResponse<T> =
        ListResponse(values = values, meta = meta)

    fun <T> ok(
        values: List<T>,
        pagination: PaginationMeta,
        meta: ResponseMeta = ResponseMeta.current(),
    ): PageResponse<T> =
        PageResponse(values = values, pagination = pagination, meta = meta)

    fun <T> ok(
        values: List<T>,
        nextCursor: String?,
        hasNext: Boolean = nextCursor != null,
        meta: ResponseMeta = ResponseMeta.current(),
    ): CursorResponse<T> =
        CursorResponse(
            values = values,
            cursor = CursorMeta(nextCursor = nextCursor, hasNext = hasNext),
            meta = meta,
        )

    fun <T> ok(
        values: List<T>,
        hasNext: Boolean,
        meta: ResponseMeta = ResponseMeta.current(),
        cursorExtractor: (T) -> Any?,
    ): CursorResponse<T> =
        ok(
            values = values,
            nextCursor = if (hasNext) values.lastOrNull()?.let(cursorExtractor)?.toString() else null,
            hasNext = hasNext,
            meta = meta,
        )

    fun <T> okData(values: List<T>, meta: ResponseMeta = ResponseMeta.current()): DataResponse<List<T>> =
        DataResponse(value = values, meta = meta)

    fun <T> list(values: List<T>, meta: ResponseMeta = ResponseMeta.current()): ListResponse<T> =
        ok(values = values, meta = meta)

    fun <T> page(
        values: List<T>,
        pagination: PaginationMeta,
        meta: ResponseMeta = ResponseMeta.current(),
    ): PageResponse<T> =
        ok(values = values, pagination = pagination, meta = meta)

    fun <T> cursor(
        values: List<T>,
        nextCursor: String?,
        hasNext: Boolean = nextCursor != null,
        meta: ResponseMeta = ResponseMeta.current(),
    ): CursorResponse<T> =
        ok(values = values, nextCursor = nextCursor, hasNext = hasNext, meta = meta)

    fun <T> cursor(
        values: List<T>,
        hasNext: Boolean,
        meta: ResponseMeta = ResponseMeta.current(),
        cursorExtractor: (T) -> Any?,
    ): CursorResponse<T> =
        ok(values = values, hasNext = hasNext, meta = meta, cursorExtractor = cursorExtractor)

    fun <T> created(
        location: URI,
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<DataResponse<T>> =
        ResponseEntity.created(location).body(ok(value, meta))

    fun <T> accepted(
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<DataResponse<T>> =
        ResponseEntity.accepted().body(ok(value, meta))

    fun noContent(): ResponseEntity<Void> =
        ResponseEntity.noContent().build()
}

object ApiResponse {
    fun <T> value(value: T, meta: ResponseMeta = ResponseMeta.current()): ApiValueResponse<T> =
        Response.ok(value = value, meta = meta)

    fun <T> list(values: List<T>, meta: ResponseMeta = ResponseMeta.current()): ApiListResponse<T> =
        Response.ok(values = values, meta = meta)

    fun <T> page(
        values: List<T>,
        pagination: PaginationMeta,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ApiPageResponse<T> =
        Response.ok(values = values, pagination = pagination, meta = meta)
}
