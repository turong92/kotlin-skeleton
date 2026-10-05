package dev.sumin.skeleton.auth.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import dev.sumin.skeleton.common.web.PublicEndpointRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.util.AntPathMatcher

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class, OpenApiCustomizer::class)
class AuthOpenApiAutoConfiguration {
    @Bean
    fun authOpenApiCustomizer(publicEndpoints: ObjectProvider<PublicEndpointRegistry>): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            // 모듈이 공개 경로로 등록한 곳(가입 · 확인 · 재설정 …)에는 bearer 요구를 붙이지 않는다 — 문서가 "로그인이 필요하다" 고 거짓말하지 않게
            val registry = publicEndpoints.getIfAvailable()
            val matcher = AntPathMatcher()
            val components = openApi.components ?: Components().also { openApi.components = it }
            components.addSecuritySchemes(
                BEARER_AUTH,
                SecurityScheme()
                    .type(SecurityScheme.Type.HTTP)
                    .scheme("bearer")
                    .bearerFormat("JWT")
                    .description("JWT bearer token issued by auth capability endpoints."),
            )

            openApi.paths.orEmpty().forEach { (path, pathItem) ->
                if (!path.requiresBearerAuth()) {
                    return@forEach
                }
                pathItem.readOperationsMap().forEach { (method, operation) ->
                    val public = registry?.endpoints.orEmpty().any { endpoint ->
                        (endpoint.method == null || endpoint.method!!.name() == method.name) && matcher.match(endpoint.pattern, path)
                    }
                    if (public) return@forEach
                    operation.addBearerAuth()
                    operation.addAuthErrorResponses()
                }
            }
        }

    private fun String.requiresBearerAuth(): Boolean =
        startsWith("/api/v1/") &&
            this !in PUBLIC_API_PATHS &&
            !startsWith("/api/v1/auth/social/") &&
            !startsWith("/api/v1/docs")

    private fun Operation.addBearerAuth() {
        val currentSecurity = security.orEmpty()
        if (currentSecurity.any { it.containsKey(BEARER_AUTH) }) {
            return
        }
        addSecurityItem(SecurityRequirement().addList(BEARER_AUTH))
    }

    private fun Operation.addAuthErrorResponses() {
        val currentResponses = responses ?: ApiResponses().also { responses = it }
        currentResponses.putIfAbsent("401", errorResponse("Unauthorized"))
        currentResponses.putIfAbsent("403", errorResponse("Forbidden"))
    }

    private fun errorResponse(description: String): ApiResponse =
        ApiResponse()
            .description(description)
            .content(
                Content().addMediaType(
                    "application/json",
                    MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/ApiError")),
                ),
            )

    private companion object {
        const val BEARER_AUTH = "bearerAuth"

        val PUBLIC_API_PATHS = setOf(
            "/api/v1/hello",
            "/api/v1/auth/login",
        )
    }
}
