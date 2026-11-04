package dev.sumin.skeleton.auth.social.openapi

import dev.sumin.skeleton.auth.api.AuthTokenResponse
import dev.sumin.skeleton.common.openapi.ApiEnvelopeType
import dev.sumin.skeleton.common.openapi.ApiResponseEnvelopeSchemas
import dev.sumin.skeleton.common.openapi.ResolvedApiResponseEnvelope
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
            components.addSchemas("OAuthSocialTokenRequest", oauthSocialTokenRequestSchema())

            val paths = openApi.paths ?: Paths().also { openApi.paths = it }
            paths.putIfAbsent(
                "/api/v1/auth/social/{provider}/login",
                PathItem().post(socialLoginOperation()),
            )
            paths.putIfAbsent(
                "/api/v1/auth/social/{provider}/token",
                PathItem().post(socialTokenOperation()),
            )
        }

    private fun socialLoginOperation(): Operation =
        socialOperation(
            operationId = "socialLogin",
            summary = "Social login",
            description = "Exchange a provider authorization code for the standard auth token response.",
            requestSchema = "OAuthSocialLoginRequest",
        )

    private fun socialTokenOperation(): Operation =
        socialOperation(
            operationId = "socialTokenLogin",
            summary = "Social login with a native SDK token",
            description = "Exchange the token a provider's native app SDK returned (idToken for Google and Apple, accessToken for Kakao and Naver; GET /auth/methods says which per provider in nativeToken) for the standard auth token response. " +
                "Providers without a native entrance answer 404.",
            requestSchema = "OAuthSocialTokenRequest",
            rejectedProofResponse = "401" to "Token rejected: AUTH.SOCIAL_ID_TOKEN_INVALID or AUTH.SOCIAL_ACCESS_TOKEN_INVALID",
        )

    private fun socialOperation(
        operationId: String,
        summary: String,
        description: String,
        requestSchema: String,
        rejectedProofResponse: Pair<String, String>? = null,
    ): Operation =
        Operation()
            .addTagsItem("auth-social")
            .operationId(operationId)
            .summary(summary)
            .description(description)
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
                            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$requestSchema")),
                        ),
                    ),
            )
            .responses(
                ApiResponses()
                    .addApiResponse(
                        "200",
                        jsonResponse(
                            "OK",
                            ApiResponseEnvelopeSchemas.operationSchema(
                                ResolvedApiResponseEnvelope(
                                    type = ApiEnvelopeType.VALUE,
                                    payloadClass = AuthTokenResponse::class.java,
                                ),
                            ),
                        ),
                    )
                    .addApiResponse("400", jsonResponse("Bad request", "#/components/schemas/ApiError"))
                    .also { responses -> rejectedProofResponse?.let { (status, text) -> responses.addApiResponse(status, jsonResponse(text, "#/components/schemas/ApiError")) } }
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
            .addProperty(
                "codeVerifier",
                StringSchema().description("PKCE (S256) code verifier of this authorization attempt: required when GET /auth/methods says pkce=REQUIRED."),
            )
            .addProperty(
                "nonce",
                StringSchema().description("The nonce of the authorization request: required when GET /auth/methods says nonce=REQUIRED."),
            )
            .addProperty(
                "state",
                StringSchema().description("The OAuth state the callback received (optional): forwarded to providers whose token request takes it (Naver), ignored by the others."),
            )
            .required(listOf("authorizationCode"))

    private fun oauthSocialTokenRequestSchema(): Schema<Any> =
        ObjectSchema()
            .description("Native SDK token login request: send exactly the one token field the provider takes (see nativeToken in GET /auth/methods).")
            .addProperty("idToken", StringSchema().description("ID token of a provider whose nativeToken is id_token (Google idToken, Apple identityToken)."))
            .addProperty("accessToken", StringSchema().description("Access token of a provider whose nativeToken is access_token (Kakao, Naver)."))
            .addProperty("nonce", StringSchema().description("The raw nonce the app made (8-256 printable ASCII). Required by Apple (the server compares its SHA-256 hex); Google compares it only when sent."))
            .addProperty("displayName", StringSchema().description("Name the app got from the SDK, for providers that leave it out of the token (Apple): used only as the nickname candidate of a new account."))

    private fun jsonResponse(description: String, schemaRef: String): ApiResponse =
        jsonResponse(description, Schema<Any>().`$ref`(schemaRef))

    private fun jsonResponse(description: String, schema: Schema<Any>): ApiResponse =
        ApiResponse()
            .description(description)
            .content(
                Content().addMediaType(
                    "application/json",
                    MediaType().schema(schema),
                ),
            )
}
