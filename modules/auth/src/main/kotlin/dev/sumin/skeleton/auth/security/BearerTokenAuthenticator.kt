package dev.sumin.skeleton.auth.security

import dev.sumin.skeleton.common.ErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication

/**
 * `Authorization: Bearer` 토큰을 JWT 가 아닌 방식(API 키 · 서비스 토큰 …)으로 읽는 고리. [JwtAuthenticationFilter] 가 JWT 로 읽기 **전에** 이 빈들에게 먼저 묻는다 -
 * 한 곳에서 Bearer 를 해석하므로 앱이 보안 체인을 바꿔도 따라온다. 빈이 없으면 필터는 예전과 같다.
 */
interface BearerTokenAuthenticator {
    /** 이 토큰이 내 것인가 - 모양(접두사)만 본다. 맞으면 JWT 로는 읽지 않는다 */
    fun supports(token: String): Boolean

    /** 인증된 [Authentication] 을 돌려주거나, 거절이면 [BearerTokenRejectedException] */
    fun authenticate(token: String, request: HttpServletRequest): Authentication
}

/** 이 토큰은 인증되지 않았다 - 필터가 [errorCode] 의 상태 · 코드로 응답을 쓰고 체인을 멈춘다 ([data] 는 응답의 `data`) */
class BearerTokenRejectedException(
    val errorCode: ErrorCode,
    val data: Any? = null,
    message: String = errorCode.title,
) : RuntimeException(message)
