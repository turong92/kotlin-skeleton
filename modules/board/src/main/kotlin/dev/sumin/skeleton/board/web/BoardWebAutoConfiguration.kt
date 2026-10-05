package dev.sumin.skeleton.board.web

import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardProperties
import dev.sumin.skeleton.board.BoardService
import dev.sumin.skeleton.board.CommentService
import dev.sumin.skeleton.board.PostService
import dev.sumin.skeleton.board.ReactionService
import dev.sumin.skeleton.common.web.PublicEndpointContributor
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
    fun boardCallers(properties: BoardProperties): BoardCallers = BoardCallers(properties)

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
}
