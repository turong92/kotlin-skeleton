package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.video.TrackStatus
import dev.sumin.skeleton.video.Video
import dev.sumin.skeleton.video.VideoStatus
import dev.sumin.skeleton.video.VideoUploads
import dev.sumin.skeleton.video.VideoVisibility
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.net.URI
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class StartVideoRequest(val bytes: Long? = null, val contentType: String? = null, val purpose: String? = null, val visibility: String? = null)

data class VideoPartsRequest(val numbers: List<Int>? = null)

data class CompletedPartRequest(val n: Int? = null, val etag: String? = null)

data class CompleteVideoRequest(val parts: List<CompletedPartRequest>? = null)

data class VideoStartedResponse(val id: String, val uploadId: String, val partSize: Long, val partCount: Int)

data class VideoPartUrl(val number: Int, val method: String, val url: String, val headers: Map<String, String>)

data class UploadedPartView(val n: Int, val etag: String, val bytes: Long)

/** 화면이 그리는 영상 한 편 — 올리는 중 · 준비 중 · 재생 가능 · (다른 기기용 판 준비 중) · 실패 */
data class VideoResponse(
    val id: String,
    val purpose: String,
    val visibility: VideoVisibility,
    val status: VideoStatus,
    /** 올리기 시작 때 말한 파일 크기 */
    val bytes: Long,
    val compat: TrackStatus,
    val ladder: TrackStatus,
    /** 변환 중일 때 0~100 */
    val progress: Int?,
    val failCode: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(video: Video) = VideoResponse(
            video.id, video.purpose, video.visibility, video.status, video.uploadBytes, video.compatStatus, video.ladderStatus, video.progress, video.failCode,
            video.srcWidth, video.srcHeight, video.srcDurationMs, video.createdAt, video.updatedAt,
        )
    }
}

/**
 * 영상 올리기 — 로그인한 사람만. 남의 · 지운 · 없는 영상은 모두 같은 404(`VIDEO.NOT_FOUND`).
 * 브라우저는 시작 → 조각 주소 → 저장소로 직접 `PUT`(응답의 `ETag` 를 모은다) → 마침 순서로 올리고, 새로 고친 뒤에는 `GET …/parts` 로 빠진 조각만 올린다.
 */
@RestController
@RequestMapping("/api/v1/videos")
@Tag(name = "Videos")
class VideoController(private val uploads: VideoUploads) {
    @Operation(
        summary = "Start a direct upload (multipart) of an mp4 / mov",
        description = "visibility is PUBLIC or PRIVATE and cannot be changed later. 413 VIDEO.TOO_LARGE (data.maxBytes) · VIDEO.QUOTA · 415 VIDEO.UNSUPPORTED · 403 VIDEO.FORBIDDEN · 429 VIDEO.TOO_MANY_UPLOADS.",
    )
    @PostMapping
    fun start(@RequestBody body: StartVideoRequest, authentication: Authentication?): ResponseEntity<DataResponse<VideoStartedResponse>> {
        val started = uploads.start(VideoCallers.account(authentication), body.bytes, body.contentType, body.purpose, body.visibility)
        return Response.created(URI.create("/api/v1/videos/${started.videoId}"), VideoStartedResponse(started.videoId, started.uploadId, started.partSize, started.partCount))
    }

    @Operation(summary = "Signed PUT addresses for the requested part numbers (at most 100 per call)")
    @PostMapping("/{id}/parts")
    fun parts(@PathVariable id: String, @RequestBody body: VideoPartsRequest, authentication: Authentication?): ListResponse<VideoPartUrl> =
        Response.ok(uploads.partUrls(VideoCallers.account(authentication), id, body.numbers).map { VideoPartUrl(it.partNumber, it.method, it.url.toString(), it.headers) })

    @Operation(summary = "The parts the store already holds - upload only the missing ones after a reload", description = "404 VIDEO.NOT_FOUND for an upload that is gone or already finished - start over")
    @GetMapping("/{id}/parts")
    fun uploadedParts(@PathVariable id: String, authentication: Authentication?): ListResponse<UploadedPartView> =
        Response.ok(uploads.uploadedParts(VideoCallers.account(authentication), id).map { UploadedPartView(it.partNumber, it.eTag, it.sizeBytes) })

    @Operation(summary = "Finish the upload (checks the stored size) and queue the video. Calling it again returns the same video")
    @PostMapping("/{id}/complete")
    fun complete(@PathVariable id: String, @RequestBody body: CompleteVideoRequest, authentication: Authentication?): DataResponse<VideoResponse> {
        val parts = body.parts?.map { (it.n ?: 0) to (it.etag ?: "") }
        return Response.ok(VideoResponse.of(uploads.complete(VideoCallers.account(authentication), id, parts)))
    }

    @Operation(summary = "One of my videos")
    @GetMapping("/{id}")
    fun get(@PathVariable id: String, authentication: Authentication?): DataResponse<VideoResponse> =
        Response.ok(VideoResponse.of(uploads.get(VideoCallers.account(authentication), id)))

    @Operation(summary = "My videos, newest first; purpose narrows to one purpose")
    @GetMapping
    fun list(@RequestParam(required = false) purpose: String?, authentication: Authentication?): ListResponse<VideoResponse> =
        Response.ok(uploads.list(VideoCallers.account(authentication), purpose).map(VideoResponse::of))

    @Operation(summary = "Delete: aborts an unfinished upload and removes the uploaded file")
    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: String, authentication: Authentication?): ResponseEntity<Void> {
        uploads.delete(VideoCallers.account(authentication), id)
        return Response.noContent()
    }
}
