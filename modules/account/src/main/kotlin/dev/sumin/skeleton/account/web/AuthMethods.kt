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
data class SocialMethodView(val provider: String, val clientId: String?, val redirectUri: String?)

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
