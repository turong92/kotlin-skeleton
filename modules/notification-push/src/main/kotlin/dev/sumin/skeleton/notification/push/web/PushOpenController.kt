package dev.sumin.skeleton.notification.push.web

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.notification.push.PushErrorCode
import dev.sumin.skeleton.notification.push.PushException
import dev.sumin.skeleton.notification.push.PushOpenReporter
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `{ id: 이벤트 id, open: 푸시 페이로드의 토큰 }` - 토큰은 비밀 취급이라 [toString] 에 싣지 않는다 */
data class PushOpenRequest(val id: String? = null, val open: String? = null) {
    override fun toString(): String = "PushOpenRequest(id=$id)"
}

/**
 * 푸시를 눌러 연 것을 알리는 보고 경로 - **로그인 없이** 부른다(서비스 워커에는 로그인 쿠키가 없다). 인증은 토큰의 서명이다.
 * 맞으면 처음이든 중복이든 같은 204, 서명 · 이벤트 id 가 틀리면 400 `PUSH.INVALID_OPEN_TOKEN`, 이미 없어진 기기는 조용히 204. 필드 길이를 제한한다(id 128자, 토큰 300자).
 * `skeleton.notification.push.open-report.secret` 이 있을 때만 [PushWebAutoConfiguration] 이 등록한다.
 */
@RestController
@RequestMapping("/api/v1/push")
@Tag(name = "Push")
class PushOpenController(private val reporter: PushOpenReporter) {
    @Operation(summary = "Report that a push notification was opened", description = "No login: the signed token from the push payload is the credential. 204 whether first or repeated.")
    @PostMapping("/opened")
    fun opened(@RequestBody request: PushOpenRequest): ResponseEntity<Void> {
        val id = request.id?.takeIf { it.isNotBlank() && it.length <= MAX_ID }
        val open = request.open?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN }
        if (id == null || open == null) throw PushException(PushErrorCode.INVALID_OPEN_TOKEN, "Invalid push open token")
        reporter.report(id, open)
        return Response.noContent()
    }

    private companion object {
        const val MAX_ID = 128
        const val MAX_TOKEN = 300
    }
}
