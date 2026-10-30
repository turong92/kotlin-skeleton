package dev.sumin.skeleton.review.web

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.CursorResponse
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.ListResponse
import dev.sumin.skeleton.common.PageResponse
import dev.sumin.skeleton.common.PaginationMeta
import dev.sumin.skeleton.common.PlatformErrorCode
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.authz.OperatorAccess
import dev.sumin.skeleton.common.upload.PrivateUploadAccess
import dev.sumin.skeleton.idempotency.IdempotentOperation
import dev.sumin.skeleton.review.ReviewPermissions
import dev.sumin.skeleton.review.ReviewErrorCode
import dev.sumin.skeleton.review.ReviewFilter
import dev.sumin.skeleton.review.ReviewInput
import dev.sumin.skeleton.review.ReviewPolicy
import dev.sumin.skeleton.review.ReviewProperties
import dev.sumin.skeleton.review.ReviewReader
import dev.sumin.skeleton.review.ReviewService
import dev.sumin.skeleton.review.ReviewSort
import dev.sumin.skeleton.review.ReviewTarget
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * 리뷰 사용자 경로. 호출자는 `Authentication.name`(auth 모듈에서는 계정 id)이다. 이 클래스는 [ReviewWebAutoConfiguration] 이 등록한다(`skeleton.review.http.enabled=false` 로 끈다).
 *
 * **대상 요약 · 일괄 요약 · 리뷰 목록의 `GET` 셋은 앱이 열면 로그인 없이 읽는다**(`skeleton.review.http.allow-anonymous-read=true` - 기본은 로그인 필요, 401).
 * `config` · `me` · 쓰기 · 도움돼요는 항상 로그인이 필요하다. 응답 어디에도 계정 id 가 없다.
 */
@RestController
@RequestMapping("\${skeleton.review.http.base-path:/api/v1/reviews}")
class ReviewController(
    private val reader: ReviewReader,
    private val service: ReviewService,
    private val properties: ReviewProperties,
    private val policy: () -> ReviewPolicy,
    /** 운영 권한 판정 - 없으면(운영 모듈 구성이 아니면) 운영 칸은 모두 false */
    private val access: OperatorAccess? = null,
    /** 사진을 짧은 서명 주소로 넘기는 자리 - 없으면 사진 경로는 404 */
    private val uploads: PrivateUploadAccess? = null,
) {
    @GetMapping("/config")
    fun config(authentication: Authentication?): DataResponse<ConfigResponse> {
        val account = accountId(authentication)
        val ref = OperatorRefs.require(authentication)
        return Response.ok(
            ConfigResponse(
                properties.targetTypes, properties.bodyMinLength, properties.bodyMaxLength, properties.maxPhotos, REPORT_REASONS,
                canModerate = access?.has(ref, ReviewPermissions.MODERATE) == true && policy().canModerate(account),
                canReply = access?.has(ref, ReviewPermissions.REPLY) == true,
            ),
        )
    }

    @GetMapping("/targets/{type}/{id}/summary")
    fun summary(authentication: Authentication?, @PathVariable type: String, @PathVariable id: String): DataResponse<SummaryResponse> =
        Response.ok(reader.summary(reader(authentication), ReviewTarget(type, id)).toResponse())

    @GetMapping("/summaries")
    fun summaries(authentication: Authentication?, @RequestParam targets: String): ListResponse<TargetRatingResponse> {
        reader(authentication)
        return Response.ok(reader.summaries(parseTargets(targets)).map { it.toResponse() })
    }

    @GetMapping("/targets/{type}/{id}/reviews")
    fun list(
        authentication: Authentication?,
        @PathVariable type: String,
        @PathVariable id: String,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) rating: Int?,
        @RequestParam(defaultValue = "false") verified: Boolean,
        @RequestParam(defaultValue = "false") photo: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<ReviewResponse> {
        val viewer = reader(authentication)
        if (page < 0 || size !in 1..MAX_SIZE) invalid("page must be >= 0 and size 1..$MAX_SIZE")
        if (rating != null && rating !in 1..5) invalid("rating must be 1..5")
        val order = ReviewSort.of(sort) ?: invalid("sort is one of ${ReviewSort.entries.joinToString { it.param }}")
        val result = reader.list(viewer, ReviewTarget(type, id), ReviewFilter(rating, verified, photo), order, page * size, size)
        return Response.ok(result.values.map { it.toResponse() }, PaginationMeta.of(page, size, result.total))
    }

    @IdempotentOperation
    @PostMapping("/targets/{type}/{id}/reviews")
    fun create(authentication: Authentication?, @PathVariable type: String, @PathVariable id: String, @RequestBody request: ReviewInput): ResponseEntity<DataResponse<ReviewResponse>> {
        val account = accountId(authentication)
        val review = service.create(account, ReviewTarget(type, id), request)
        val location = ServletUriComponentsBuilder.fromCurrentContextPath().path(basePath()).path("/{id}").buildAndExpand(review.id).toUri()
        return Response.created(location, own(account, review.id))
    }

    @PatchMapping("/{id}")
    fun update(authentication: Authentication?, @PathVariable id: String, @RequestBody request: ReviewInput): DataResponse<ReviewResponse> {
        val account = accountId(authentication)
        val review = service.update(account, id, request)
        return Response.ok(own(account, review.id))
    }

    @DeleteMapping("/{id}")
    fun remove(authentication: Authentication?, @PathVariable id: String): ResponseEntity<Void> {
        service.remove(accountId(authentication), id)
        return Response.noContent()
    }

    @PutMapping("/{id}/helpful")
    fun helpful(authentication: Authentication?, @PathVariable id: String): DataResponse<HelpfulResponse> =
        service.setHelpful(accountId(authentication), id, true).let { Response.ok(HelpfulResponse(it.helpfulCount, it.helpful)) }

    @DeleteMapping("/{id}/helpful")
    fun unhelpful(authentication: Authentication?, @PathVariable id: String): DataResponse<HelpfulResponse> =
        service.setHelpful(accountId(authentication), id, false).let { Response.ok(HelpfulResponse(it.helpfulCount, it.helpful)) }

    @PostMapping("/{id}/reports")
    fun report(authentication: Authentication?, @PathVariable id: String, @RequestBody request: ReportRequest): ResponseEntity<Void> {
        service.report(accountId(authentication), id, request.reason, request.detail)
        return Response.noContent()
    }

    /** 사진 한 칸 - 그 리뷰를 볼 수 있는 로그인한 사람에게 짧은 서명 주소로 302. 못 보면 없는 것과 같은 404 */
    @GetMapping("/{id}/photos/{index}")
    fun photo(authentication: Authentication?, @PathVariable id: String, @PathVariable index: Int): ResponseEntity<Void> {
        val account = accountId(authentication)
        val ref = OperatorRefs.require(authentication)
        val moderator = access?.has(ref, ReviewPermissions.MODERATE) == true && policy().canModerate(account)
        val key = reader.photoKey(account, moderator, id, index)
        val signer = uploads?.takeIf { it.enabled() }
        if (key == null || signer == null) throw ApplicationException("Photo not found", ReviewErrorCode.NOT_FOUND)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, signer.downloadUrl(key, signer.defaultTtl))
            .cacheControl(CacheControl.empty().cachePrivate())
            .build()
    }

    @GetMapping("/me")
    fun mine(authentication: Authentication?, @RequestParam(required = false) cursor: String?, @RequestParam(defaultValue = "20") size: Int): CursorResponse<MyReviewResponse> {
        if (size !in 1..MAX_SIZE) invalid("size must be 1..$MAX_SIZE")
        val page = reader.mine(accountId(authentication), cursor?.takeIf { it.isNotBlank() }, size)
        return Response.ok(page.values.map { it.toMyResponse() }, page.nextCursor, page.hasNext)
    }

    /** 방금 쓴 내 줄을 응답 모양으로 - 이름 · 도움돼요 칸을 목록과 같은 길로 채운다 */
    private fun own(account: String, id: String): ReviewResponse = checkNotNull(reader.one(account, id)) { "review $id vanished" }.toResponse()

    private fun basePath() = properties.http.basePath

    /** 읽기 셋의 보는 사람 - 앱이 열었으면 익명은 null, 아니면 로그인이 필요하다 */
    private fun reader(authentication: Authentication?): String? =
        if (properties.http.allowAnonymousRead) authentication.signedIn() else accountId(authentication)

    private fun accountId(authentication: Authentication?): String =
        authentication.signedIn() ?: throw ApplicationException("Authentication required", PlatformErrorCode.UNAUTHORIZED)

    private fun Authentication?.signedIn(): String? = this?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name

    private fun parseTargets(raw: String): List<ReviewTarget> {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.size > MAX_TARGETS) invalid("targets is 1..$MAX_TARGETS comma separated type:id")
        return parts.map {
            val at = it.indexOf(':')
            if (at <= 0 || at == it.length - 1) invalid("target must be type:id")
            ReviewTarget(it.substring(0, at), it.substring(at + 1))
        }
    }

    private fun invalid(message: String): Nothing = throw ApplicationException(message, PlatformErrorCode.PARAMETER_VALIDATION_FAILED)

    private companion object {
        const val MAX_SIZE = 100
        const val MAX_TARGETS = 50
    }
}
