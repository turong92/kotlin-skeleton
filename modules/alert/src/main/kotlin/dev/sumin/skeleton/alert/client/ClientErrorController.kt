package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.openapi.NoContentOperation
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 웹 · 앱이 자기 오류를 보내는 곳 — 로그인 없이 된다(로그인 화면에서 난 오류도 알아야 한다). 열어 주는 것은 [ClientErrorAutoConfiguration] 의 공개 경로 기여 하나뿐이다.
 * 본문은 `@RequestBody` 로 받지 않고 [ClientErrorIntake] 가 상한까지만 직접 읽는다 — 형식(JSON · `sendBeacon` 의 text/plain)을 가리지 않고, 큰 본문을 통째로 올리지 않는다.
 * 답은 `204`, 몸 없음(보낸 기기는 답에 기대지 않는다). 거절은 `400`/`413`/`429` 표준 오류 본문.
 */
@RestController
@RequestMapping("/api/v1/client-errors")
class ClientErrorController(
    private val intake: ClientErrorIntake,
    private val clientIps: ClientIps,
) {
    @PostMapping
    @NoContentOperation
    fun receive(http: HttpServletRequest): ResponseEntity<Void> {
        intake.receive(clientIps.of(http).limitKey, http.getHeader("User-Agent"), http.contentLengthLong, http.inputStream)
        return Response.noContent()
    }
}
