package dev.sumin.skeleton.app.sample.notes

import dev.sumin.skeleton.video.web.VideoOperatorPolicy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 변환 일꾼 토큰을 발급 · 폐기하는 운영자 경로(`/api/v1/videos/admin` 아래)는 모듈 기본이 「모두 거절」이다 - 이 앱은 ADMIN 역할에 열어 준다.
 * 운영 일꾼의 토큰은 이 경로로 한 번 발급해 일꾼 선언의 비밀로 넣는다(docs/deploy.md 「영상 변환 일꾼」).
 */
@Configuration(proxyBeanMethods = false)
class NoteVideoOperator {
    @Bean
    fun videoOperatorPolicy(): VideoOperatorPolicy = VideoOperatorPolicy { authentication -> authentication.authorities.any { it.authority == "ROLE_ADMIN" } }
}
