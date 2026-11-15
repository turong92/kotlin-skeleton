package dev.sumin.skeleton.app.sample

import dev.sumin.skeleton.account.AccountProperties
import dev.sumin.skeleton.auth.magiclink.MagicLinkProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource

/**
 * 견본 앱의 민감 경로 속도 제한(가입 · 로그인 · 비밀번호 찾기 · 인증 코드 · 로그인 링크)이 **실제로 바인딩되는 키**인지 — 키를 잘못 적으면 스프링은 조용히 무시해
 * 모듈 기본이 그대로 남고, 한도를 조인 줄 알았던 앱이 느슨하게 나간다. main 의 `application.yml` 만 읽는다(시험 설정은 모듈 기본으로 되돌려 둔다 — `config/application.yml`).
 */
class SensitiveRateLimitConfigTest {
    private val binder = Binder(ConfigurationPropertySources.from(YamlPropertySourceLoader().load("sample-main", ClassPathResource("application.yml")).toList()))

    @Test
    fun `the sample tightens the sensitive paths below the module defaults, with keys that really bind`() {
        val signUp = binder.bindOrCreate("skeleton.account.sign-up", AccountProperties.SignUp::class.java)
        val login = binder.bindOrCreate("skeleton.account.login", AccountProperties.Login::class.java)
        val verification = binder.bindOrCreate("skeleton.account.verification", AccountProperties.Verification::class.java)
        val reset = binder.bindOrCreate("skeleton.account.reset", AccountProperties.Reset::class.java)
        val magicLink = binder.bindOrCreate("skeleton.auth-magic-link", MagicLinkProperties::class.java)

        assertEquals(listOf(5, 20, 5, 20, 5, 5), listOf(signUp.perIp, login.perIp, login.perAccount, verification.attemptsPerIp, reset.perIp, magicLink.perIp))
        assertTrue(signUp.perIp < AccountProperties.SignUp().perIp)
        assertTrue(login.perIp < AccountProperties.Login().perIp && login.perAccount < AccountProperties.Login().perAccount)
        assertTrue(verification.attemptsPerIp < AccountProperties.Verification().attemptsPerIp)
        assertTrue(reset.perIp < AccountProperties.Reset().perIp)
        assertTrue(magicLink.perIp < MagicLinkProperties().perIp)
        // 메일을 받는 주소 쪽 한도(주소 하나에 몇 통)는 모듈 기본 그대로다 - 메일 폭탄을 막는 값이라 조이지 않아도 작다
        assertEquals(AccountProperties.Verification().perEmail, verification.perEmail)
        assertEquals(AccountProperties.Reset().perEmail, reset.perEmail)
        assertEquals(MagicLinkProperties().perEmail, magicLink.perEmail)
    }
}
