package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.video.LadderRung
import dev.sumin.skeleton.video.ProbeResult
import dev.sumin.skeleton.video.ResultFile
import dev.sumin.skeleton.video.UploadUrl
import dev.sumin.skeleton.video.VideoJob
import dev.sumin.skeleton.video.VideoJobResult
import dev.sumin.skeleton.video.VideoJobs
import dev.sumin.skeleton.video.VideoLane
import dev.sumin.skeleton.video.VideoNotFoundException
import dev.sumin.skeleton.video.VideoWorker
import dev.sumin.skeleton.video.VideoWorkerInvalidException
import dev.sumin.skeleton.video.VideoWorkerService
import dev.sumin.skeleton.video.VideoWorkerUnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.net.URI
import java.time.Instant
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

data class ClaimRequest(val workerId: String? = null, val lanes: List<String>? = null)

data class HeartbeatRequest(val workerId: String? = null, val progress: Int? = null)

data class HeartbeatResponse(val leaseUntil: Instant)

data class UploadUrlsRequest(val workerId: String? = null, val names: List<String>? = null)

data class CompleteJobRequest(
    val workerId: String? = null,
    val orig: ResultFile? = null,
    val poster: ResultFile? = null,
    val compat: ResultFile? = null,
    val probe: ProbeResult? = null,
    val compatible: Boolean? = null,
    val ladderBuild: String? = null,
    val rungs: List<LadderRung>? = null,
)

data class FailJobRequest(val workerId: String? = null, val code: String? = null, val detail: String? = null, val retryable: Boolean? = null)

/**
 * 일꾼 전용 API. 인증은 `Authorization: Bearer <일꾼 토큰>` — 인자 [VideoWorker] 를 받는 것만으로 걸린다(사용자 로그인은 여기서 맞지 않는다).
 * 일꾼은 저장소 키를 갖지 않는다: 입력은 서버가 서명한 읽기 주소, 결과는 허용 이름으로만 서명한 올리기 주소. 응답은 `{value: …}` 봉투(목록은 `{values: […]}`)
 */
@RestController
@RequestMapping("/api/internal/video")
@Tag(name = "Video worker")
class VideoWorkerController(private val jobs: VideoJobs) {
    @Operation(summary = "Take the next job (204 when there is none)")
    @PostMapping("/claim")
    fun claim(worker: VideoWorker, @RequestBody request: ClaimRequest): ResponseEntity<DataResponse<VideoJob>> {
        val lanes = request.lanes?.let { names -> names.map { n -> VideoLane.entries.firstOrNull { it.name == n } ?: throw VideoWorkerInvalidException("lanes") }.toSet() }
        val job = jobs.claim(worker, request.workerId.orEmpty(), lanes) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(Response.ok(job))
    }

    @Operation(summary = "Extend the lease; progress is 0-100 (409 VIDEO_WORKER.LEASE_LOST when the lease is gone)")
    @PostMapping("/jobs/{jobId}/heartbeat")
    fun heartbeat(worker: VideoWorker, @PathVariable jobId: String, @RequestBody request: HeartbeatRequest): DataResponse<HeartbeatResponse> =
        Response.ok(HeartbeatResponse(jobs.heartbeat(worker, request.workerId.orEmpty(), jobId, request.progress)))

    @Operation(summary = "Signed PUT addresses for result files - only the lane's allowed names, otherwise 400 VIDEO_WORKER.PATH_NOT_ALLOWED and nothing is signed")
    @PostMapping("/jobs/{jobId}/upload-urls")
    fun uploadUrls(worker: VideoWorker, @PathVariable jobId: String, @RequestBody request: UploadUrlsRequest): ListResponse<UploadUrl> =
        Response.ok(jobs.uploadUrls(worker, request.workerId.orEmpty(), jobId, request.names.orEmpty()))

    @Operation(summary = "Report the finished result - the lane is told by which field is present (orig / compat / ladderBuild)")
    @PostMapping("/jobs/{jobId}/complete")
    fun complete(worker: VideoWorker, @PathVariable jobId: String, @RequestBody request: CompleteJobRequest): ResponseEntity<Void> {
        jobs.complete(
            worker, request.workerId.orEmpty(), jobId,
            VideoJobResult(request.orig, request.poster, request.compat, request.probe, request.compatible, request.ladderBuild, request.rungs),
        )
        return Response.noContent()
    }

    @Operation(summary = "Report a failure - code is one of UNSUPPORTED_FORMAT, CORRUPT, LIMITS_EXCEEDED, TOO_BIG_FOR_WORKER, PROCESSING_FAILED")
    @PostMapping("/jobs/{jobId}/fail")
    fun fail(worker: VideoWorker, @PathVariable jobId: String, @RequestBody request: FailJobRequest): ResponseEntity<Void> {
        jobs.fail(worker, request.workerId.orEmpty(), jobId, request.code.orEmpty(), request.detail, request.retryable == true)
        return Response.noContent()
    }
}

/** `Authorization: Bearer <일꾼 토큰>` 을 [VideoWorker] 로 바꾼다. 토큰 · 헤더 값은 로그에 찍지 않는다 */
class VideoWorkerResolver(private val workers: VideoWorkerService) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == VideoWorker::class.java

    override fun resolveArgument(parameter: MethodParameter, mavContainer: ModelAndViewContainer?, webRequest: NativeWebRequest, binderFactory: WebDataBinderFactory?): VideoWorker {
        val header = webRequest.getHeader(HttpHeaders.AUTHORIZATION)
        val presented = header?.takeIf { it.startsWith(BEARER) }?.removePrefix(BEARER)?.trim()
        if (presented.isNullOrEmpty()) throw VideoWorkerUnauthorizedException()
        return workers.authenticate(presented) ?: throw VideoWorkerUnauthorizedException()
    }

    private companion object {
        const val BEARER = "Bearer "
    }
}

class VideoWorkerWebConfigurer(private val resolver: VideoWorkerResolver) : WebMvcConfigurer {
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(resolver)
    }
}

/** 운영자만 일꾼 토큰을 다룰 수 있다. 기본 정책은 **모두 거절**이라 앱이 빈으로 허락을 정한다(예: 관리자 권한) */
fun interface VideoOperatorPolicy {
    fun isOperator(authentication: Authentication): Boolean
}

data class IssueWorkerRequest(val name: String? = null, val lanes: List<String>? = null)

data class VideoWorkerView(val id: Long, val name: String, val lanes: List<String>, val createdAt: Instant, val lastSeenAt: Instant?, val revokedAt: Instant?) {
    companion object {
        fun of(w: VideoWorker) = VideoWorkerView(w.id, w.name, w.lanes.map { it.name }.sorted(), w.createdAt, w.lastSeenAt, w.revokedAt)
    }
}

/** 발급 응답 — 평문 [token] 은 이때 한 번만 나온다 */
data class IssuedWorkerResponse(val id: Long, val name: String, val lanes: List<String>, val createdAt: Instant, val token: String)

/** 운영자 — 일꾼 토큰 발급 · 목록 · 폐기. 토큰은 발급 응답에만 나온다(표에는 해시뿐) */
@RestController
@RequestMapping("/api/v1/videos/admin/workers")
@Tag(name = "Video workers (operator)")
class VideoWorkerAdminController(private val workers: VideoWorkerService, private val policy: VideoOperatorPolicy) {
    @Operation(summary = "Issue a worker token for the given lanes (REMUX, COMPAT, LADDER) - the token is shown once")
    @PostMapping
    fun issue(@RequestBody body: IssueWorkerRequest, authentication: Authentication?): ResponseEntity<DataResponse<IssuedWorkerResponse>> {
        operator(authentication)
        val lanes = body.lanes.orEmpty().map { n -> VideoLane.entries.firstOrNull { it.name == n } ?: throw VideoWorkerInvalidException("lanes") }.toSet()
        val issued = workers.issue(body.name.orEmpty(), lanes)
        return Response.created(URI.create("/api/v1/videos/admin/workers/${issued.worker.id}"), IssuedWorkerResponse(issued.worker.id, issued.worker.name, VideoWorkerView.of(issued.worker).lanes, issued.worker.createdAt, issued.token))
    }

    @Operation(summary = "Workers, oldest first (never the token)")
    @GetMapping
    fun list(authentication: Authentication?): ListResponse<VideoWorkerView> {
        operator(authentication)
        return Response.ok(workers.list().map(VideoWorkerView::of))
    }

    @Operation(summary = "Revoke a worker token - it stops working at once")
    @DeleteMapping("/{id}")
    fun revoke(@PathVariable id: Long, authentication: Authentication?): ResponseEntity<Void> {
        operator(authentication)
        if (!workers.revoke(id)) throw VideoNotFoundException()
        return Response.noContent()
    }

    private fun operator(authentication: Authentication?) {
        VideoCallers.account(authentication)   // 로그인하지 않았으면 401
        if (!policy.isOperator(authentication!!)) throw ApplicationException("operator only", PlatformErrorCode.FORBIDDEN)
    }
}
