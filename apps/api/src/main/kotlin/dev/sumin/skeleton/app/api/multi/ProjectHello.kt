package dev.sumin.skeleton.app.api.multi

import dev.sumin.skeleton.common.DataResponse
import dev.sumin.skeleton.common.Response
import dev.sumin.skeleton.common.project.ProjectApi
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// 프로젝트 전용 API 의 견본 (docs/multi-project.md). 프로젝트 모듈은 표시를 한 번 만들고 컨트롤러에는 그것만 붙인다.
// 두 프로젝트가 **같은 경로**를 쓴다 — 그래서 `multi` 프로필에서만 등록한다 (한 서비스에서는 같은 경로가 모호하다).
@ProjectApi("a")
annotation class ProjectAApi

@ProjectApi("b")
annotation class ProjectBApi

data class ProjectHelloResponse(val project: String, val message: String)

@Profile("multi")
@RestController
@RequestMapping("/api/v1/project-hello")
@ProjectAApi
class ProjectAHelloController {
    @GetMapping
    fun hello(): DataResponse<ProjectHelloResponse> = Response.ok(ProjectHelloResponse("a", "Hello from project A"))
}

@Profile("multi")
@RestController
@RequestMapping("/api/v1/project-hello")
@ProjectBApi
class ProjectBHelloController {
    @GetMapping
    fun hello(): DataResponse<ProjectHelloResponse> = Response.ok(ProjectHelloResponse("b", "Hello from project B"))
}

@Profile("multi")
@Configuration(proxyBeanMethods = false)
class ProjectHelloPublicEndpointConfiguration {
    @Bean
    fun projectHelloPublicEndpoint(): PublicEndpointContributor = PublicEndpointContributor { it.add("GET", "/api/v1/project-hello") }
}
