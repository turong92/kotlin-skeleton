package dev.sumin.skeleton.auth.social.oauth

import dev.sumin.skeleton.common.FieldValidationException

/** 앱의 네이티브 SDK 가 주는 토큰의 종류 — 제공자마다 하나다. [wire] 는 `GET /auth/methods` 의 `nativeToken` 값 */
enum class NativeTokenKind(val wire: String) {
    /** OIDC ID 토큰 (구글 `idToken` · 애플 `identityToken`) — 요청의 `idToken` 필드 */
    ID_TOKEN("id_token"),

    /** 제공자 API 에 물어 확인하는 액세스 토큰 (카카오 · 네이버) — 요청의 `accessToken` 필드 */
    ACCESS_TOKEN("access_token"),
}

/**
 * 앱이 넘긴 토큰 한 건. [token] 은 [NativeTokenKind] 에 맞는 값 하나, [nonce] 는 앱이 만든 **원문**(제공자가 해시로 비교하면 제공자 모듈이 바꾼다),
 * [displayName] 은 애플처럼 이름을 토큰에 싣지 않는 제공자를 위해 앱이 넘기는 값(새 계정의 닉네임 후보로만 쓴다).
 * [toString] 은 값을 가린다 (로그 · 예외에 토큰이 남지 않게).
 */
data class OAuthNativeToken(
    val token: String,
    val nonce: String? = null,
    val displayName: String? = null,
) {
    override fun toString() = "OAuthNativeToken(token=<redacted>)"
}

/** 제공자에게 사용자를 증명하는 두 길 — 인가 코드, 앱의 네이티브 토큰. 로그인 · 연결 · 다시 인증이 [resolveProfile] 하나로 프로필을 얻는다 */
sealed interface OAuthCredential {
    data class Code(val exchange: OAuthCodeExchange) : OAuthCredential {
        override fun toString() = "OAuthCredential.Code(<redacted>)"
    }

    data class Native(val token: OAuthNativeToken) : OAuthCredential {
        override fun toString() = "OAuthCredential.Native(<redacted>)"
    }
}

/**
 * 요청의 토큰 필드를 이 제공자의 규칙에 맞춰 검사해 [OAuthCredential.Native] 를 만든다. 제공자를 부르기 **전**(그리고 다시 인증 증거를 쓰기 전)에 불러야 한다 — 틀리면 아무것도 쓰이지 않는다.
 * - 토큰 입구가 없는 제공자: [OAuthProviderNotFoundException] (404)
 * - [NativeTokenKind] 에 맞는 필드가 없거나 8192자를 넘거나 다른 필드도 왔다: [FieldValidationException] (`400 COMMON.VALIDATION_FAILED`)
 * - nonce 모양이 틀리거나 REQUIRED 인데 없다: [OAuthNonceException] (400). 제공자가 nonce 를 쓰지 않으면(UNSUPPORTED) 버린다
 */
fun OAuthProvider.nativeCredential(
    idToken: String?,
    accessToken: String?,
    nonce: String?,
    displayName: String?,
): OAuthCredential.Native {
    val kind = nativeToken ?: throw OAuthProviderNotFoundException(providerId)
    val id = idToken?.takeIf { it.isNotBlank() }
    val access = accessToken?.takeIf { it.isNotBlank() }
    val (expectedField, token, otherField, other) = when (kind) {
        NativeTokenKind.ID_TOKEN -> Quad("idToken", id, "accessToken", access)
        NativeTokenKind.ACCESS_TOKEN -> Quad("accessToken", access, "idToken", id)
    }
    if (token == null) throw FieldValidationException(expectedField, "Required", "$expectedField is required for this provider")
    if (token.length > MAX_NATIVE_TOKEN_LENGTH) throw FieldValidationException(expectedField, "TooLong", "$expectedField must be at most $MAX_NATIVE_TOKEN_LENGTH characters")
    if (other != null) throw FieldValidationException(otherField, "NotAllowed", "$otherField is not accepted by this provider: send only $expectedField")
    val n = checkedNonce(nonce)
    return OAuthCredential.Native(
        OAuthNativeToken(
            token = token,
            nonce = n.takeIf { this.nonce != NonceMode.UNSUPPORTED },
            displayName = displayName?.trim()?.takeIf { it.isNotEmpty() },
        ),
    )
}

/** 8KB — 서버 · 프록시의 요청 헤더 상한이 보통 8KB 라 JWT 도 그 안쪽이다(구글 ID 토큰은 1~2KB). 제공자를 부르기 전에 끊는 방어선 */
private const val MAX_NATIVE_TOKEN_LENGTH = 8192

private data class Quad(val expectedField: String, val token: String?, val otherField: String, val other: String?)

/**
 * 제공자를 불러 사용자의 프로필을 얻는다 — 로그인 · 연결 · 다시 인증이 같이 쓰는 "제공자 호출 → 예외 갈래" 한 곳.
 * 증명이 틀렸다는 답([OAuthInvalidAuthorizationCodeException]: 코드 · ID 토큰 · 액세스 토큰 거절)은 그대로 올리고, 나머지 실패(제공자 · JWKS 장애, 설정 오류)는 [OAuthProviderGatewayException] (502) 으로 바꾼다.
 */
fun OAuthProvider.resolveProfile(credential: OAuthCredential): OAuthUserProfile =
    try {
        when (credential) {
            is OAuthCredential.Code -> fetchProfile(credential.exchange)
            is OAuthCredential.Native -> verifyNativeToken(credential.token)
        }
    } catch (ex: OAuthInvalidAuthorizationCodeException) {
        throw ex
    } catch (ex: RuntimeException) {
        throw OAuthProviderGatewayException(providerId, ex)
    }
