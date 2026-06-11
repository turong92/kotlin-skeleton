package dev.sumin.skeleton.common

import java.net.URI
import org.springframework.http.ResponseEntity

object ApiResponseEntity {
    fun <T> created(
        location: URI,
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<ApiValueResponse<T>> =
        Response.created(location, value, meta)

    fun <T> accepted(
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<ApiValueResponse<T>> =
        Response.accepted(value, meta)

    fun noContent(): ResponseEntity<Void> =
        Response.noContent()
}
