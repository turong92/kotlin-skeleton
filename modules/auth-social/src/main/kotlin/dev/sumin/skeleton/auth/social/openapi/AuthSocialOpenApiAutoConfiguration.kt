package dev.sumin.skeleton.auth.social.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.Paths
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.parameters.PathParameter
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class, OpenApiCustomizer::class)
class AuthSocialOpenApiAutoConfiguration {
    @Bean
    fun authSocialOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val components = openApi.components ?: Components().also { openApi.components = it }
            components.addSchemas("OAuthSocialLoginRequest", oauthSocialLoginRequestSchema())

            val paths = openApi.paths ?: Paths().also { openApi.paths = it }
            paths.putIfAbsent(
                "/api/v1/auth/social/{provider}/login",
                PathItem().post(socialLoginOperation()),
            )
        }

    private fun socialLoginOperation(): Operation =
        Operation()
            .addTagsItem("auth-social")
            .operationId("socialLogin")
            .summary("Social login")
            .description("Exchange a provider authorization code for the standard auth token response.")
            .addParametersItem(
                PathParameter()
                    .name("provider")
                    .description("OAuth provider id, such as google, kakao, naver, or fake in tests.")
                    .required(true)
                    .schema(StringSchema()),
            )
            .addHeader(
                name = "traceparent",
                description = "W3C trace context.",
                pattern = "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
            )
            .addHeader("X-Trace-Id", "Optional traceId override.", "^[0-9a-f]{32}$")
            .addHeader("X-Span-Id", "Optional caller span id.", "^[0-9a-f]{16}$")
            .requestBody(
                RequestBody()
                    .required(true)
                    .content(
                        Content().addMediaType(
                            "application/json",
                            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/OAuthSocialLoginRequest")),
                        ),
                    ),
            )
            .responses(
                ApiResponses()
                    .addApiResponse("200", jsonResponse("OK", "#/components/schemas/ApiValueResponseAuthTokenResponse"))
                    .addApiResponse("400", jsonResponse("Bad request", "#/components/schemas/ApiError"))
                    .addApiResponse("404", jsonResponse("Resource not found", "#/components/schemas/ApiError"))
                    .addApiResponse("500", jsonResponse("Internal server error", "#/components/schemas/ApiError")),
            )

    private fun Operation.addHeader(
        name: String,
        description: String,
        pattern: String,
    ): Operation =
        addParametersItem(
            HeaderParameter()
                .name(name)
                .description(description)
                .required(false)
                .schema(StringSchema().pattern(pattern)),
        )

    private fun oauthSocialLoginRequestSchema(): Schema<Any> =
        ObjectSchema()
            .description("Provider authorization-code login request.")
            .addProperty(
                "authorizationCode",
                StringSchema().description("Authorization code issued by the provider."),
            )
            .addProperty(
                "redirectUri",
                StringSchema().description("Redirect URI used by the frontend authorization flow."),
            )
            .required(listOf("authorizationCode"))

    private fun jsonResponse(description: String, schemaRef: String): ApiResponse =
        ApiResponse()
            .description(description)
            .content(
                Content().addMediaType(
                    "application/json",
                    MediaType().schema(Schema<Any>().`$ref`(schemaRef)),
                ),
            )
}
