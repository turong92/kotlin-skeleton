package dev.sumin.skeleton.app.sample

import dev.sumin.skeleton.auth.account.AuthAccount
import dev.sumin.skeleton.auth.account.AuthAccountRepository
import dev.sumin.skeleton.auth.account.InMemoryAuthAccountRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * 데모 계정 — auth 의 기본 시드(user · admin)에 게시판 운영자(`MODERATOR`)를 하나 더한 것이다.
 * 계정 저장소는 앱의 몫이라 모듈을 고치지 않고 이 빈 하나로 바꾼다. 실제 서비스에서는 자기 계정 저장소로 교체한다
 * (보호 프로필 prod · staging 은 비밀번호가 `password` 인 시드를 쓰는 앱을 막지 않으므로, 이 클래스를 그대로 배포하지 않는다).
 */
@Configuration(proxyBeanMethods = false)
class SampleAccounts {
    @Bean
    fun sampleAuthAccountRepository(passwordEncoder: PasswordEncoder): AuthAccountRepository {
        fun hash() = requireNotNull(passwordEncoder.encode("password"))
        return InMemoryAuthAccountRepository(
            listOf(
                AuthAccount("acc_user", "user", "user@example.com", hash(), setOf("USER")),
                AuthAccount("acc_admin", "admin", "admin@example.com", hash(), setOf("USER", "ADMIN")),
                AuthAccount("acc_moderator", "moderator", "moderator@example.com", hash(), setOf("USER", "MODERATOR")),
            ),
        )
    }
}
