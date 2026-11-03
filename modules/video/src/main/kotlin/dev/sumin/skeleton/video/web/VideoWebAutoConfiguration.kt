package dev.sumin.skeleton.video.web

import dev.sumin.skeleton.common.web.BearerIgnoredPaths
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.video.VideoAutoConfiguration
import dev.sumin.skeleton.video.VideoJobs
import dev.sumin.skeleton.video.VideoLibrary
import dev.sumin.skeleton.video.VideoPlaylists
import dev.sumin.skeleton.video.VideoPosters
import dev.sumin.skeleton.video.VideoUploads
import dev.sumin.skeleton.video.VideoWorkerService
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 영상 HTTP — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있고 올리기 서비스([VideoUploads] 빈)가 있을 때만.
 * `/api/v1/videos` 는 `skeleton.video.http.enabled=false` 로 끈다 (앱이 자기 컨트롤러를 둘 때). 일꾼 API(`/api/internal/video/...`)와 운영자 일꾼 발급은 그 스위치와 상관없이 선다.
 */
@AutoConfiguration(after = [VideoAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnBean(VideoUploads::class)
class VideoWebAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "skeleton.video.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun videoController(uploads: VideoUploads, library: VideoLibrary): VideoController = VideoController(uploads, library)

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.video.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun videoPosterController(posters: VideoPosters): VideoPosterController = VideoPosterController(posters)

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.video.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun videoPlaylistController(playlists: VideoPlaylists): VideoPlaylistController = VideoPlaylistController(playlists)

    @Bean
    @ConditionalOnProperty(prefix = "skeleton.video.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean
    fun videoAdminController(library: VideoLibrary, policy: VideoOperatorPolicy): VideoAdminController = VideoAdminController(library, policy)

    /** 비공개 영상의 재생 목록 GET 만 로그인 없이 — 경로의 출입증이 인증이다 */
    @Bean
    @ConditionalOnMissingBean(name = ["videoPlaylistPublicEndpointContributor"])
    fun videoPlaylistPublicEndpointContributor(): PublicEndpointContributor = PublicEndpointContributor { registry -> registry.add("GET", "/api/v1/videos/play/**") }

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerController(jobs: VideoJobs): VideoWorkerController = VideoWorkerController(jobs)

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerResolver(workers: VideoWorkerService): VideoWorkerResolver = VideoWorkerResolver(workers)

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerWebConfigurer(resolver: VideoWorkerResolver): VideoWorkerWebConfigurer = VideoWorkerWebConfigurer(resolver)

    /** 운영자 정책의 기본은 모두 거절 — 앱이 빈으로 허락을 정한다 */
    @Bean
    @ConditionalOnMissingBean(VideoOperatorPolicy::class)
    fun denyVideoOperatorPolicy(): VideoOperatorPolicy = VideoOperatorPolicy { false }

    @Bean
    @ConditionalOnMissingBean
    fun videoWorkerAdminController(workers: VideoWorkerService, policy: VideoOperatorPolicy): VideoWorkerAdminController = VideoWorkerAdminController(workers, policy)

    /** 일꾼 API 는 사용자 로그인 없이 열린다 — 일꾼 토큰이 인증이다(컨트롤러가 인자로 받는 것만으로 걸린다) */
    @Bean
    @ConditionalOnMissingBean(name = ["videoWorkerPublicEndpointContributor"])
    fun videoWorkerPublicEndpointContributor(): PublicEndpointContributor = PublicEndpointContributor { registry -> registry.add("POST", "/api/internal/video/**") }

    /** 일꾼 토큰을 JWT 필터가 로그인 토큰으로 검증하려다 401 을 내지 않게 한다 */
    @Bean
    @ConditionalOnMissingBean(name = ["videoWorkerBearerIgnored"])
    fun videoWorkerBearerIgnored(): BearerIgnoredPaths = BearerIgnoredPaths { listOf("/api/internal/video/**") }
}
