package dev.sumin.skeleton.auth.sessions

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.session.LoginSessionIssuer
import dev.sumin.skeleton.auth.session.OpenedSession
import dev.sumin.skeleton.auth.session.SessionRevoker
import dev.sumin.skeleton.common.time.TimeProvider
import dev.sumin.skeleton.common.web.ClientIps
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.time.Duration
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** 요청에서 기기 정보를 읽는다: IP 는 `ClientIps`(프록시 규칙 적용), UA, 선택 헤더 `X-Device-Name` */
class SessionClients(private val clientIps: ClientIps) {
    fun of(request: HttpServletRequest?): SessionClient =
        if (request == null) SessionClient(null, null, null)
        else SessionClient(clientIps.of(request).ip, request.getHeader(HttpHeaders.USER_AGENT), request.getHeader(DEVICE_NAME_HEADER)?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        const val DEVICE_NAME_HEADER = "X-Device-Name"
    }
}

/** 리프레시 토큰을 클라이언트에 내려 주고 · 요청에서 읽는 방식(body | cookie) 한 곳 */
class RefreshTokenDelivery(private val properties: AuthSessionProperties, private val time: TimeProvider) {
    val cookieMode: Boolean get() = properties.delivery == AuthSessionProperties.Delivery.COOKIE

    /** 쿠키 전달이면 쿠키를 심고 본문에는 토큰을 싣지 않는다. body 전달이면 그대로 */
    fun deliver(session: OpenedSession, response: HttpServletResponse?): OpenedSession {
        val token = session.refreshToken
        if (!cookieMode || token == null) return session
        if (response != null) {
            val maxAge = session.refreshExpiresAt?.let { Duration.between(time.now(), it) } ?: properties.absoluteTtl
            response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, maxAge).toString())
        }
        return session.copy(refreshToken = null)
    }

    fun clear(response: HttpServletResponse) {
        if (cookieMode) response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
    }

    /** 요청에서 토큰을 읽는다 — cookie 모드는 쿠키만, body 모드는 본문 값만 (한 방식만 열어 둔다) */
    fun read(request: HttpServletRequest, bodyToken: String?): String? =
        if (cookieMode) request.cookies?.firstOrNull { it.name == properties.cookie.name }?.value?.takeIf { it.isNotEmpty() }
        else bodyToken?.takeIf { it.isNotEmpty() }

    /** cookie 모드에서 쿠키를 쓰는 요청(refresh · logout)은 브라우저가 다른 출처에서 몰래 붙일 수 없는 헤더를 요구한다 */
    fun csrfOk(request: HttpServletRequest): Boolean = !cookieMode || request.getHeader(properties.cookie.csrfHeader) != null

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(properties.cookie.name, value)
            .httpOnly(true).secure(properties.cookie.secure).sameSite(properties.cookie.sameSite).path(properties.cookie.path).maxAge(maxAge).build()
}

/** 로그인 방법이 무엇이든(`AuthTokenResponseFactory` 를 지나는 모든 로그인) 세션을 연다 */
class SessionLoginIssuer(
    private val sessions: SessionService,
    private val delivery: RefreshTokenDelivery,
    private val clients: SessionClients,
) : LoginSessionIssuer {
    override fun open(account: AuthAccount): OpenedSession {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val opened = sessions.open(account.accountId, clients.of(attributes?.request))
        return delivery.deliver(opened, attributes?.response)
    }
}

class SessionRevokerAdapter(private val sessions: SessionService) : SessionRevoker {
    override fun revokeAll(accountId: String, exceptSessionId: String?) = sessions.revokeAll(accountId, exceptSessionId)
}
