package dev.sumin.skeleton.common

import java.net.URI
import org.springframework.http.ResponseEntity

object ApiResponseEntity {
    fun <T> created(
        location: URI,
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<ApiValueResponse<T>> =
        ResponseEntity.created(location).body(ApiResponse.value(value, meta))

    fun <T> accepted(
        value: T,
        meta: ResponseMeta = ResponseMeta.current(),
    ): ResponseEntity<ApiValueResponse<T>> =
        ResponseEntity.accepted().body(ApiResponse.value(value, meta))

    fun noContent(): ResponseEntity<Void> =
        ResponseEntity.noContent().build()
}
