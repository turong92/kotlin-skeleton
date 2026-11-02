package dev.sumin.skeleton.storage.image.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelope
import dev.sumin.skeleton.common.upload.UploadImageState
import dev.sumin.skeleton.common.upload.UploadImages
import dev.sumin.skeleton.storage.ObjectKey
import dev.sumin.skeleton.storage.StorageKeyOwnership
import dev.sumin.skeleton.storage.web.StorageErrorCode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import java.time.Duration
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class StorageImagesRequest(@field:NotEmpty @field:Size(max = MAX_KEYS) val keys: List<String>) {
    companion object {
        const val MAX_KEYS = 20
    }
}

data class StorageImageVariantView(val url: String, val width: Int, val height: Int)

data class StorageImageItem(val key: String, val status: String, val width: Int?, val height: Int?, val variants: Map<String, StorageImageVariantView>)

data class StorageImagesResponse(val items: List<StorageImageItem>)

/**
 * 내 이미지의 변환 상태 · 변환본 주소 - 편집 화면의 썸네일 · 「처리 중」 표시용. **내 키만** 답한다(남의 키 · 모르는 키 · 이미지가 아닌 키는 빠진다 - 서명도 하지 않는다).
 * 줄이 없는 내 이미지 키는 지연 등록(`ensure`)을 걸고 `PENDING` 으로 답한다.
 */
@RestController
@RequestMapping("/api/v1/storage")
@Tag(name = "Storage")
class StorageImagesController(
    private val images: UploadImages,
    private val ownership: StorageKeyOwnership,
    private val accessTtl: Duration,
) {
    @Operation(
        summary = "Status and signed URLs of the caller's own image variants",
        description = "At most 20 keys. Keys that are not the caller's, not images or unknown are left out. A key with no status row yet is registered and answered PENDING.",
    )
    @ApiResponseEnvelope(type = ApiEnvelopeType.VALUE, value = StorageImagesResponse::class)
    @PostMapping("/images")
    fun images(
        authentication: Authentication?, @Valid @RequestBody request: StorageImagesRequest,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
        response: HttpServletResponse,
    ): Any {
        response.setHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT)   // 서명 주소의 형식(WebP 여부)이 Accept 에 따라 달라진다
        val accountId = authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name
            ?: throw ApplicationException("Authentication required", StorageErrorCode.UNAUTHENTICATED)
        val own = request.keys.distinct().filter { raw -> runCatching { ObjectKey(raw) }.isSuccess && ownership.owns(accountId, raw) }
        val items = own.mapNotNull { key ->
            val status = images.status(key) ?: run { images.ensure(key); images.status(key) } ?: return@mapNotNull null
            val variants = if (status.state != UploadImageState.READY) emptyMap() else status.variants.mapNotNull { (name, v) ->
                images.signedVariantUrl(key, name, accessTtl, accept)?.let { name to StorageImageVariantView(it, v.width, v.height) }
            }.toMap()
            StorageImageItem(key, status.state.name, status.width, status.height, variants)
        }
        return Response.ok(StorageImagesResponse(items))
    }
}
