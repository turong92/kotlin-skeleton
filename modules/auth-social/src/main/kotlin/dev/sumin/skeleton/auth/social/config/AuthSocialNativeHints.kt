package dev.sumin.skeleton.auth.social.config

import dev.sumin.skeleton.auth.social.api.OAuthSocialLoginRequest
import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지: 소셜 로그인 경로는 컨트롤러 메서드가 아니라 **함수형 라우트**(`RouterFunction`)라서 스프링 AOT 가 요청 본문 타입을 찾지 못한다 —
 * 없으면 `Cannot construct instance of OAuthSocialLoginRequest` 로 500 이다 (docs/native-image.md "리플렉션 규칙"). `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 */
class AuthSocialNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), OAuthSocialLoginRequest::class.java)
    }
}
