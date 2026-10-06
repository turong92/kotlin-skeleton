package dev.sumin.skeleton.auth.social.oauth

/** 이 제공자가 PKCE(S256)를 어떻게 다루나 — 프론트는 `GET /auth/methods` 의 `pkce` 를 보고 `code_challenge` 를 붙일지 정한다 */
enum class PkceMode {
    /** 검증기 없이는 로그인 · 연결이 400 AUTH.SOCIAL_PKCE_FAILED */
    REQUIRED,

    /** 프론트가 `code_challenge` 를 보냈다면 검증기를 제공자에 넘긴다. 없어도 된다 */
    SUPPORTED,

    /** 제공자가 모른다 — 검증기가 와도 넘기지 않는다 */
    UNSUPPORTED,
}

/** ID 토큰의 `nonce` 를 어떻게 다루나 (프론트가 인가 요청에 실은 값을 로그인 요청에도 보낸다) */
enum class NonceMode { REQUIRED, SUPPORTED, UNSUPPORTED }

/**
 * 인가 코드 한 건의 교환 입력. [codeVerifier] 는 [PkceMode] 가 허용한 만큼만, [nonce] 는 [NonceMode] 가 허용한 만큼만 채워져 제공자에 온다
 * (검증은 [OAuthProvider.codeExchange] 가 이미 했다).
 */
data class OAuthCodeExchange(
    val authorizationCode: String,
    val redirectUri: String? = null,
    val codeVerifier: String? = null,
    val nonce: String? = null,
) {
    override fun toString() = "OAuthCodeExchange(authorizationCode=<redacted>, codeVerifier=<redacted>)"
}

/** 프론트가 인가 URL 을 만드는 데 필요한 제공자 쪽 값 — 비밀이 아니다 (`GET /auth/methods`). 모르면 null 을 돌려 프론트가 자기 설정을 쓴다 */
data class OAuthAuthorizeInfo(
    val url: String,
    val scopes: List<String>,
    /** 인가 URL 에 그대로 붙일 고정 파라미터 (예: `response_type=code`) */
    val params: Map<String, String> = emptyMap(),
)

interface OAuthProvider {
    val providerId: String

    /** PKCE 정책. 기본 UNSUPPORTED — 기존 제공자는 그대로 */
    val pkce: PkceMode get() = PkceMode.UNSUPPORTED

    val nonce: NonceMode get() = NonceMode.UNSUPPORTED

    /**
     * true 면 `skeleton.auth-social.providers.<id>.enabled` 없이도 이 빈이 있다는 것만으로 켜진 제공자다 (자기 모듈의 client id 설정이 켜는 스위치).
     * 기본 false — 기존 제공자는 계속 `enabled=true` 를 요구한다.
     */
    val autoEnabled: Boolean get() = false

    /** `GET /auth/methods` 에 실을 공개 client id · redirect URI. null 이면 `skeleton.auth-social.providers.<id>` 의 값을 쓴다 */
    val publicClientId: String? get() = null
    val publicRedirectUri: String? get() = null

    val authorize: OAuthAuthorizeInfo? get() = null

    fun fetchProfile(authorizationCode: String, redirectUri: String?): OAuthUserProfile

    /** PKCE · nonce 를 아는 제공자는 이것을 구현한다. 기본은 옛 두 인자 메서드 (검증기 · nonce 를 쓰지 않는다) */
    fun fetchProfile(exchange: OAuthCodeExchange): OAuthUserProfile =
        fetchProfile(exchange.authorizationCode, exchange.redirectUri)
}

private val VERIFIER_FORMAT = Regex("^[A-Za-z0-9\\-._~]{43,128}$") // RFC 7636 §4.1
private val NONCE_FORMAT = Regex("^[\\x21-\\x7E]{8,256}$")

/**
 * 요청의 검증기 · nonce 를 이 제공자의 정책에 맞춰 검사해 제공자에 넘길 입력을 만든다. 제공자를 부르기 **전**에 불러야 한다 —
 * 틀리면 [OAuthPkceException] · [OAuthNonceException] (400)이고 한 번 쓰는 인가 코드는 아직 쓰이지 않았다.
 */
fun OAuthProvider.codeExchange(
    authorizationCode: String,
    redirectUri: String?,
    codeVerifier: String?,
    nonce: String?,
): OAuthCodeExchange {
    val verifier = codeVerifier?.takeIf { it.isNotEmpty() }
    if (verifier != null && !VERIFIER_FORMAT.matches(verifier)) throw OAuthPkceException(providerId)
    if (pkce == PkceMode.REQUIRED && verifier == null) throw OAuthPkceException(providerId)
    val n = nonce?.takeIf { it.isNotEmpty() }
    if (n != null && !NONCE_FORMAT.matches(n)) throw OAuthNonceException(providerId)
    if (this.nonce == NonceMode.REQUIRED && n == null) throw OAuthNonceException(providerId)
    return OAuthCodeExchange(
        authorizationCode = authorizationCode,
        redirectUri = redirectUri,
        codeVerifier = verifier.takeIf { pkce != PkceMode.UNSUPPORTED },
        nonce = n.takeIf { this.nonce != NonceMode.UNSUPPORTED },
    )
}
