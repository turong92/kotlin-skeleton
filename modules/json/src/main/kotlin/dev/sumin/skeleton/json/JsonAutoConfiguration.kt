package dev.sumin.skeleton.json

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.ObjectSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import tools.jackson.databind.ObjectMapper

@AutoConfiguration
class JsonAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun jsonCodec(objectMapper: ObjectProvider<ObjectMapper>): JsonCodec =
        JacksonJsonCodec(objectMapper.getIfAvailable { JacksonJsonCodec.defaultObjectMapper() })

    @Bean
    @ConditionalOnMissingBean
    fun jsonPayloadRegistry(
        jsonCodec: JsonCodec,
        definitions: ObjectProvider<JsonPayloadDefinition<*>>,
        migrators: ObjectProvider<JsonPayloadMigrator>,
    ): JsonPayloadRegistry =
        JsonPayloadRegistry(
            codec = jsonCodec,
            definitions = definitions.orderedStream().toList(),
            migrators = migrators.orderedStream().toList(),
        )

    @Bean
    @ConditionalOnMissingBean
    fun jsonDocumentWritingConverter(jsonCodec: JsonCodec): JsonDocumentWritingConverter =
        JsonDocumentWritingConverter(jsonCodec)

    @Bean
    @ConditionalOnMissingBean
    fun jsonDocumentReadingConverter(jsonCodec: JsonCodec): JsonDocumentReadingConverter =
        JsonDocumentReadingConverter(jsonCodec)

    @Bean
    @ConditionalOnMissingBean
    fun versionedJsonDocumentWritingConverter(jsonCodec: JsonCodec): VersionedJsonDocumentWritingConverter =
        VersionedJsonDocumentWritingConverter(jsonCodec)

    @Bean
    @ConditionalOnMissingBean
    fun versionedJsonDocumentReadingConverter(jsonCodec: JsonCodec): VersionedJsonDocumentReadingConverter =
        VersionedJsonDocumentReadingConverter(jsonCodec)

    @Bean
    @ConditionalOnClass(OpenAPI::class, OpenApiCustomizer::class)
    fun jsonOpenApiCustomizer(): OpenApiCustomizer =
        OpenApiCustomizer { openApi ->
            val components = openApi.components ?: Components().also { openApi.components = it }
            components.addSchemas("JsonDocument", jsonDocumentSchema())
            components.addSchemas("VersionedJsonDocument", versionedJsonDocumentSchema())
        }

    private fun jsonDocumentSchema(): Schema<Any> =
        ObjectSchema()
            .description("Arbitrary JSON object managed through the skeleton JsonDocument contract.")
            .additionalProperties(true)

    private fun versionedJsonDocumentSchema(): Schema<Any> =
        ObjectSchema()
            .description("Versioned JSON payload envelope for DB, event, and internal payload storage.")
            .addProperty("type", StringSchema().example("payment.provider-result"))
            .addProperty("version", IntegerSchema().minimum(1.toBigDecimal()).example(1))
            .addProperty("payload", Schema<Any>().`$ref`("#/components/schemas/JsonDocument"))
            .addProperty(
                "metadata",
                ObjectSchema()
                    .additionalProperties(StringSchema())
                    .example(mapOf("source" to "provider")),
            )
            .required(listOf("type", "version", "payload"))
}
