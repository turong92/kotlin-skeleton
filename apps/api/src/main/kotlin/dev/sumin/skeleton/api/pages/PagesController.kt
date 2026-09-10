package dev.sumin.skeleton.api.pages

import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable

/**
 * `/api/` 밖의 HTML 페이지가 platform/auth 와 공존하는 예시 (Thymeleaf 를 붙이면 String 뷰 이름을 돌려주면 된다).
 *
 * - 인증: auth 의 `anyRequest().authenticated()` 는 [PublicEndpointContributor] 로 `/pages/` 하위를 열어 우회
 * - rate limit: 기본 `skeleton.web.rate-limit.path-pattern` 이 `/api/` 하위라 페이지에는 안 걸림
 * - 에러: [GlobalExceptionHandler] 는 `@RestControllerAdvice` 라 JSON 을 돌려준다 → 페이지 컨트롤러 전용
 *   [HtmlPageErrorAdvice] 가 먼저 잡아 text/html 로 응답 (`assignableTypes` + 최우선 순서)
 */
@Controller
class PagesController {
    @GetMapping("/pages/hello", produces = [MediaType.TEXT_HTML_VALUE])
    fun hello(): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body("<!doctype html><h1>hello page</h1>")

    @GetMapping("/pages/boom/{reason}", produces = [MediaType.TEXT_HTML_VALUE])
    fun boom(@PathVariable reason: String): ResponseEntity<String> = throw IllegalStateException(reason)
}

@ControllerAdvice(assignableTypes = [PagesController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class HtmlPageErrorAdvice {
    @ExceptionHandler(Exception::class)
    fun html(ex: Exception): ResponseEntity<String> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.TEXT_HTML)
            .body("<!doctype html><h1>문제가 생겼어요</h1>")
}

@Configuration(proxyBeanMethods = false)
class PagesPublicEndpointConfiguration {
    @Bean
    fun pagesPublicEndpoints(): PublicEndpointContributor =
        PublicEndpointContributor { registry -> registry.add("/pages/**") }
}
