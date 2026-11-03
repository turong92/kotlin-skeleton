package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.video.VideoInvalidException
import dev.sumin.skeleton.video.VideoPosters
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** [key] 는 파일 묶음(`/api/v1/storage`)으로 올려 완료한 내 이미지의 저장소 키 */
data class SetVideoPosterRequest(val key: String? = null)

/** 올린 사람이 영상 포스터를 바꿔 끼운다 — 이미지 모듈이 있는 조립에서만 쓸 수 있다 */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Videos")
class VideoPosterController(private val posters: VideoPosters) {
    @Operation(
        summary = "Use one of my uploaded images as this video's poster",
        description = "The image goes through the file bundle's image path (upload check, metadata removal, display copy) and only the checked copy becomes the poster; the next playback grant points at it. " +
            "404 for someone else's video or image · 409 VIDEO.POSTER_PENDING while the image is still being checked (ask again shortly) · 409 VIDEO.NOT_READY before the video is playable · " +
            "422 VIDEO.POSTER_REJECTED for an image the check refused (or when the app has no image module).",
    )
    @PutMapping("/{id}/poster")
    fun replace(@PathVariable id: String, @RequestBody body: SetVideoPosterRequest, authentication: Authentication?): ResponseEntity<Void> {
        posters.replace(VideoCallers.account(authentication), id, body.key?.takeIf { it.isNotBlank() } ?: throw VideoInvalidException("key"))
        return Response.noContent()
    }
}
