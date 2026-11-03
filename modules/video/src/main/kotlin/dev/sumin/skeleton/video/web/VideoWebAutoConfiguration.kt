package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.VideoUploads
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 영상 HTTP — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 올리기 서비스([VideoUploads] 빈)가 있을 때만.
 * `/api/v1/videos` 는 `skeleton.video.http.enabled=false` 로 끈다 (앱이 자기 컨트롤러를 둘 때).
 */
@AutoConfiguration(after = [VideoAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(VideoUploads::class)
class VideoWebAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.video.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun videoController(uploads: VideoUploads): VideoController = VideoController(uploads)
}
