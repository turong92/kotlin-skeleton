package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.BoardService
import dev.sumin.skeleton.board.CommentService
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.ReactionService
import dev.sumin.skeleton.common.web.ClientIps
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean

/**
 * 게시판 HTTP 엔드포인트(`skeleton.board.http.base-path`, 기본 `/api/v1/boards`) — 서블릿 웹 앱이고 Spring Security(호출자)가 클래스패스에 있을 때만.
 * `skeleton.board.http.enabled=false` 로 끈다. 인증은 앱의 보안 설정이 건다 (auth 의 기본 체인은 모든 경로에 인증을 요구한다) —
 * `skeleton.board.http.allow-anonymous-read=true` 면 GET 경로를 공개 경로로 연다.
 */
@AutoConfiguration(after = [BoardAutoConfiguration::class])
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = ["org.springframework.security.core.Authentication"])
@ConditionalOnProperty(prefix = "skeleton.board.http", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BoardWebAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun boardCallers(properties: BoardProperties, clientIps: ObjectProvider<ClientIps>): BoardCallers =
        BoardCallers(properties, clientIps.getIfAvailable { ClientIps() })

    @Bean
    @ConditionalOnMissingBean
    fun boardController(service: BoardService, callers: BoardCallers): BoardController = BoardController(service, callers)

    @Bean
    @ConditionalOnMissingBean
    fun postController(service: PostService, reactions: ReactionService, callers: BoardCallers, properties: BoardProperties): PostController =
        PostController(service, reactions, callers, properties)

    @Bean
    @ConditionalOnMissingBean
    fun commentController(service: CommentService, reactions: ReactionService, callers: BoardCallers, properties: BoardProperties): CommentController =
        CommentController(service, reactions, callers, properties)

    @Bean
    @ConditionalOnMissingBean(name = ["boardPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.board.http", name = ["allow-anonymous-read"], havingValue = "true")
    fun boardPublicEndpointContributor(properties: BoardProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", properties.http.basePath)
            registry.add("GET", "${properties.http.basePath}/**")
        }

    /**
     * `skeleton.board.guest.enabled=true` 일 때만 쓰기 경로를 인증 없이 연다 — 그래도 게시판의 `guest_write` 가 실제로 정한다 (NONE 이면 서비스가 지금과 같은 401).
     * 열린 경로에서도 인증된 요청은 지금처럼 인증된다.
     */
    @Bean
    @ConditionalOnMissingBean(name = ["boardGuestPublicEndpointContributor"])
    @ConditionalOnProperty(prefix = "skeleton.board.guest", name = ["enabled"], havingValue = "true")
    fun boardGuestPublicEndpointContributor(properties: BoardProperties): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            val base = properties.http.basePath
            registry.add("POST", "$base/*/posts")
            registry.add("PATCH", "$base/*/posts/*")
            registry.add("DELETE", "$base/*/posts/*")
            registry.add("POST", "$base/*/posts/*/comments")
            registry.add("PATCH", "$base/*/posts/*/comments/*")
            registry.add("DELETE", "$base/*/posts/*/comments/*")
            registry.add("PUT", "$base/*/posts/*/comments/*/moderation")
            registry.add("PUT", "$base/*/posts/*/comments/*/revision")
            registry.add("GET", "$base/*/posts/*/attachments")   // 비회원 글 주인 · 작성자가 승인 전 사진을 비밀 헤더로 본다
            registry.add("GET", "$base/*/posts/*/comments/*/attachments")
        }
}
