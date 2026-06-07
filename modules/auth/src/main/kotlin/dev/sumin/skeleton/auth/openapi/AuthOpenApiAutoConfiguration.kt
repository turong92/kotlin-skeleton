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
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class, OpenApiCustomizer::class)
class AuthOpenApiAutoConfiguration {
    @Bean
    fun authOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
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
                pathItem.readOperations().forEach { operation ->
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
