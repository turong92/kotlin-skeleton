package dev.sumin.skeleton.common.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.headers.Header
import io.swagger.v3.oas.models.media.ArraySchema
import io.swagger.v3.oas.models.media.BooleanSchema
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.HeaderParameter
import io.swagger.v3.oas.models.responses.ApiResponse
import io.swagger.v3.oas.models.responses.ApiResponses
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.web.method.HandlerMethod

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class, OpenApiCustomizer::class)
@EnableConfigurationProperties(OpenApiProperties::class)
class PlatformOpenApiAutoConfiguration {
    @Bean
    fun platformOpenApiCustomizer(properties: OpenApiProperties): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            openApi.info = (openApi.info ?: Info())
                .title(properties.title)
                .version(properties.version)
                .description(properties.description)

            val components = openApi.components ?: Components().also { openApi.components = it }
            components.addSchemas("ApiError", apiErrorSchema())
            components.addSchemas("ResponseMeta", responseMetaSchema())
            components.addSchemas("PaginationMeta", paginationMetaSchema())
            ApiResponseEnvelopeSchemas.componentSchemas().forEach { (name, schema) ->
                components.addSchemas(name, schema)
            }
        }

    @Bean
    fun standardOperationCustomizer(): GlobalOperationCustomizer =
        GlobalOperationCustomizer { operation, handlerMethod ->
            operation.addHeaderParameter(
                name = "traceparent",
                description = "W3C trace context. Reuse this to keep one traceId across a full flow.",
                pattern = "^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$",
            )
            operation.addHeaderParameter(
                name = "X-Trace-Id",
                description = "Optional traceId override for clients that do not yet send W3C traceparent.",
                pattern = "^[0-9a-f]{32}$",
            )
            operation.addHeaderParameter(
                name = "X-Span-Id",
                description = "Optional caller span id. The server still creates and returns its own span id.",
                pattern = "^[0-9a-f]{16}$",
            )
            operation.addStandardErrorResponses()
            operation.applyStandardOperationResponse(handlerMethod)
            operation.applyResponseEnvelope(handlerMethod)
            operation.normalizeJsonResponseContent()
            operation
        }

    private fun Operation.addHeaderParameter(
        name: String,
        description: String,
        pattern: String,
    ) {
        val currentParameters = parameters.orEmpty()
        if (currentParameters.any { it.name == name && it.`in` == "header" }) {
            return
        }
        addParametersItem(
            HeaderParameter()
                .name(name)
                .description(description)
                .required(false)
                .schema(StringSchema().pattern(pattern)),
        )
    }

    private fun Operation.addStandardErrorResponses() {
        val currentResponses = responses ?: ApiResponses().also { responses = it }
        currentResponses.putIfAbsent("400", errorResponse("Bad request"))
        currentResponses.putIfAbsent("404", errorResponse("Resource not found"))
        currentResponses.putIfAbsent("500", errorResponse("Internal server error"))
    }

    private fun Operation.applyStandardOperationResponse(handlerMethod: HandlerMethod) {
        when {
            handlerMethod.hasMethodAnnotation(CreatedOperation::class.java) -> {
                val created = moveResponse(from = "200", to = "201", description = "Created")
                created.addHeaderObject(
                    "Location",
                    Header()
                        .description("Created resource URI")
                        .schema(StringSchema().format("uri")),
                )
            }

            handlerMethod.hasMethodAnnotation(AcceptedOperation::class.java) -> {
                moveResponse(from = "200", to = "202", description = "Accepted")
            }

            handlerMethod.hasMethodAnnotation(NoContentOperation::class.java) -> {
                val currentResponses = responses ?: ApiResponses().also { responses = it }
                currentResponses.remove("200")
                currentResponses.putIfAbsent("204", ApiResponse().description("No content"))
            }
        }
    }

    private fun Operation.applyResponseEnvelope(handlerMethod: HandlerMethod) {
        val envelope = ApiResponseEnvelopeResolver.resolve(handlerMethod.method) ?: return
        if (handlerMethod.hasMethodAnnotation(NoContentOperation::class.java)) {
            return
        }
        val response = successResponse() ?: return
        val content = response.content ?: Content().also { response.content = it }
        val mediaType = content["application/json"] ?: content["*/*"] ?: MediaType()
        mediaType.schema(ApiResponseEnvelopeSchemas.operationSchema(envelope, mediaType.schema))
        content.addMediaType("application/json", mediaType)
        content.remove("*/*")
    }

    private fun Operation.successResponse(): ApiResponse? {
        val currentResponses = responses ?: return null
        return listOf("200", "201", "202")
            .firstNotNullOfOrNull { code -> currentResponses[code] }
            ?: currentResponses.entries.firstOrNull { (code, _) -> code.startsWith("2") }?.value
    }

    private fun Operation.moveResponse(
        from: String,
        to: String,
        description: String,
    ): ApiResponse {
        val currentResponses = responses ?: ApiResponses().also { responses = it }
        val source = currentResponses.remove(from) ?: ApiResponse()
        source.description(description)
        currentResponses.putIfAbsent(to, source)
        return currentResponses[to] ?: source
    }

    private fun Operation.normalizeJsonResponseContent() {
        responses?.values?.forEach { response ->
            val content = response.content ?: return@forEach
            val wildcardMediaType = content["*/*"] ?: return@forEach
            content.putIfAbsent("application/json", wildcardMediaType)
            content.remove("*/*")
        }
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

    private fun apiErrorSchema(): Schema<Any> =
        ObjectSchema()
            .description("Standard error response with stable code and trace/span fields.")
            .addProperty("code", StringSchema().example("AUTH.INVALID_CREDENTIALS"))
            .addProperty("title", StringSchema().example("Unauthorized"))
            .addProperty("status", IntegerSchema().example(401))
            .addProperty("detail", StringSchema().example("Invalid credentials"))
            .addProperty("traceId", StringSchema().pattern("^[0-9a-f]{32}$"))
            .addProperty("spanId", StringSchema().pattern("^[0-9a-f]{16}$"))
            .addProperty("timestamp", StringSchema().format("date-time"))
            .addProperty("data", ObjectSchema().description("Optional machine-readable error context."))
            .addProperty(
                "errors",
                ArraySchema().items(
                    ObjectSchema()
                        .addProperty("field", StringSchema().example("email"))
                        .addProperty("code", StringSchema().example("INVALID_FORMAT"))
                        .addProperty("message", StringSchema().example("email format invalid")),
                ),
            )
            .required(listOf("code", "title", "status", "timestamp"))

    private fun responseMetaSchema(): Schema<Any> =
        ObjectSchema()
            .description("Metadata attached to every successful API response.")
            .addProperty("traceId", StringSchema().pattern("^[0-9a-f]{32}$"))
            .addProperty("spanId", StringSchema().pattern("^[0-9a-f]{16}$"))
            .addProperty("timestamp", StringSchema().format("date-time"))
            .required(listOf("timestamp"))

    private fun paginationMetaSchema(): Schema<Any> =
        ObjectSchema()
            .description("Offset page metadata for paginated API responses.")
            .addProperty("page", IntegerSchema().minimum(0.toBigDecimal()))
            .addProperty("size", IntegerSchema().minimum(1.toBigDecimal()))
            .addProperty("totalElements", IntegerSchema().format("int64").minimum(0.toBigDecimal()))
            .addProperty("totalPages", IntegerSchema().minimum(0.toBigDecimal()))
            .addProperty("hasNext", BooleanSchema())
            .addProperty("hasPrevious", BooleanSchema())
            .required(listOf("page", "size", "totalElements", "totalPages", "hasNext", "hasPrevious"))
}
