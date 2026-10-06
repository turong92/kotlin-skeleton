package dev.sumin.skeleton.account.web

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.account.SignInMethods
import dev.sumin.skeleton.account.abuse.CaptchaGate
import dev.sumin.skeleton.account.signin.SignInMethodRegistry
import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.Duration
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SignUpOptions(val password: Boolean, val emailVerification: Boolean, val social: Boolean)

/** 소셜 로그인을 시작하려면 프론트에 필요한 것 — 둘 다 비밀이 아니다 (client id 는 인가 URL 에 그대로 실린다) */
data class SocialMethodView(
    val provider: String,
    val clientId: String?,
    val redirectUri: String?,
    /** `REQUIRED` | `SUPPORTED` | `UNSUPPORTED` — PKCE(S256): REQUIRED/SUPPORTED 면 인가 URL 에 `code_challenge` · `code_challenge_method=S256` 을 붙이고 로그인 요청에 `codeVerifier` 를 보낸다 */
    val pkce: String = "UNSUPPORTED",
    /** 같은 값 집합 — REQUIRED/SUPPORTED 면 인가 URL 에 `nonce` 를 붙이고 로그인 요청에 같은 `nonce` 를 보낸다 */
    val nonce: String = "UNSUPPORTED",
    /** 인가 URL 을 만드는 데 필요한 제공자 쪽 값. 모르는 제공자는 null (프론트가 자기 설정을 쓴다) */
    val authorize: SocialAuthorizeView? = null,
)

data class SocialAuthorizeView(val url: String, val scopes: List<String>, val params: Map<String, String>)

/** 이 백엔드가 지금 켜 둔 로그인 방법 — 프론트가 환경변수로 손으로 맞추지 않게. 계정 · 주소 정보는 없다 (모두에게 같은 답) */
data class AuthMethodsView(
    val methods: List<String>,
    val signUp: SignUpOptions,
    val social: List<SocialMethodView>,
    val captchaRequired: Boolean,
    /** `body` | `cookie` — `auth-session` 이 없으면 null (리프레시 토큰이 없다) */
    val refreshDelivery: String?,
)

/** 소셜 제공자 목록을 대는 쪽 — `auth-social` 이 있을 때만 [AccountSocialWebAutoConfiguration] 이 빈으로 둔다 */
fun interface SocialMethodsSource {
    fun enabled(): List<SocialMethodView>
}

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Account")
class AuthMethodsController(
    private val registry: SignInMethodRegistry,
    private val props: AccountProperties,
    private val captcha: CaptchaGate,
    private val social: () -> List<SocialMethodView>,
    private val refreshDelivery: () -> String?,
) {
    @Operation(summary = "Sign-in methods this backend has enabled (public, cacheable, the same answer for everyone)")
    @GetMapping("/methods")
    fun methods(): ResponseEntity<DataResponse<AuthMethodsView>> {
        val socials = social()
        val codes = registry.all().map { it.code }.filter { code -> code == SignInMethods.PASSWORD || code == SignInMethods.MAGIC_LINK }
            .sortedBy { if (it == SignInMethods.PASSWORD) 0 else 1 }
        val view = AuthMethodsView(
            methods = codes,
            signUp = SignUpOptions(props.signUp.enabled, props.signUp.emailVerification, props.social.signUp),
            social = socials,
            captchaRequired = props.captcha.required,
            refreshDelivery = refreshDelivery(),
        )
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic()).body(Response.ok(view))
    }
}
