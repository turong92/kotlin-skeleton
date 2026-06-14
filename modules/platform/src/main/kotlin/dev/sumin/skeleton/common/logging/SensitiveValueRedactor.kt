package dev.sumin.skeleton.common.logging

import java.time.temporal.TemporalAccessor
import java.util.Date
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

class SensitiveValueRedactor(
    private val replacement: String = "[REDACTED]",
    sensitiveNames: Set<String> = DEFAULT_SENSITIVE_NAMES,
) {
    private val exactNames = sensitiveNames.map { normalize(it) }.toSet()
    private val objectMapper: ObjectMapper = JsonMapper.builder().build()

    constructor(properties: RedactionProperties) : this(
        replacement = properties.replacement,
        sensitiveNames = DEFAULT_SENSITIVE_NAMES + properties.additionalSensitiveNames,
    )

    fun redact(name: String, value: String?): String? {
        if (value == null) return null
        return if (isSensitive(name)) replacement else value
    }

    fun redact(values: Map<String, String?>): Map<String, String?> =
        values.mapValues { (name, value) -> redact(name, value) }

    fun redactHeaders(values: Map<String, List<String>>): Map<String, List<String>> =
        values.mapValues { (name, headerValues) ->
            if (isSensitive(name)) listOf(replacement) else headerValues
        }

    fun redactQueryString(query: String): String =
        query
            .split("&")
            .joinToString("&") { pair -> redactQueryPair(pair) }

    fun redactJsonString(raw: String): String =
        runCatching {
            objectMapper.writeValueAsString(redactJsonNode(null, objectMapper.readTree(raw)))
        }.getOrDefault(raw)

    fun redactJsonString(value: Any): String =
        objectMapper.writeValueAsString(redactObjectValue(value, value::class, sensitive = false))

    fun isSensitive(name: String): Boolean {
        val normalized = normalize(name)
        return exactNames.any { normalized == it || normalized.endsWith(it) } ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password")
    }

    private fun redactQueryPair(pair: String): String {
        val separatorIndex = pair.indexOf("=")
        if (separatorIndex < 0) {
            return if (isSensitive(pair)) "$pair=$replacement" else pair
        }
        val name = pair.substring(0, separatorIndex)
        val value = pair.substring(separatorIndex + 1)
        return "$name=${redact(name, value)}"
    }

    private fun redactJsonNode(
        name: String?,
        node: JsonNode,
    ): JsonNode {
        if (name != null && isSensitive(name)) {
            return objectMapper.valueToTree(replacement)
        }
        return when {
            node.isObject -> redactJsonObject(node.asObject())
            node.isArray -> redactJsonArray(node.asArray())
            else -> node.deepCopy()
        }
    }

    private fun redactJsonObject(node: ObjectNode): ObjectNode =
        objectMapper.createObjectNode().also { redacted ->
            node.properties().forEach { (name, value) ->
                redacted.set(name, redactJsonNode(name, value))
            }
        }

    private fun redactJsonArray(node: ArrayNode): ArrayNode =
        objectMapper.createArrayNode().also { redacted ->
            node.values().forEach { value -> redacted.add(redactJsonNode(null, value)) }
        }

    private fun redactObjectValue(
        value: Any?,
        valueClass: KClass<*>?,
        sensitive: Boolean,
    ): Any? {
        if (value == null) return null
        if (sensitive) return replacement

        return when (value) {
            is String -> value
            is Number -> value
            is Boolean -> value
            is Enum<*> -> value.name
            is TemporalAccessor -> value.toString()
            is Date -> value.toInstant().toString()
            is UUID -> value.toString()
            is Map<*, *> -> value.entries.associate { (entryKey, entryValue) ->
                val key = entryKey?.toString().orEmpty()
                key to if (isSensitive(key)) replacement else redactObjectValue(entryValue, entryValue?.let { it::class }, sensitive = false)
            }
            is Iterable<*> -> value.map { item -> redactObjectValue(item, item?.let { it::class }, sensitive = false) }
            is Array<*> -> value.map { item -> redactObjectValue(item, item?.let { it::class }, sensitive = false) }
            else -> redactProperties(value, valueClass ?: value::class)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redactProperties(
        value: Any,
        valueClass: KClass<*>,
    ): Map<String, Any?> =
        valueClass.memberProperties.associate { property ->
            val typedProperty = property as KProperty1<Any, *>
            property.name to redactObjectValue(
                value = typedProperty.get(value),
                valueClass = property.returnType.classifier as? KClass<*>,
                sensitive = property.findAnnotation<Sensitive>() != null || hasSensitiveJavaField(valueClass, property.name),
            )
        }

    private fun hasSensitiveJavaField(
        valueClass: KClass<*>,
        propertyName: String,
    ): Boolean =
        runCatching {
            valueClass.java.getDeclaredField(propertyName).isAnnotationPresent(Sensitive::class.java)
        }.getOrDefault(false)

    private fun normalize(name: String): String =
        name
            .trim()
            .replace("-", "")
            .replace("_", "")
            .lowercase()

    companion object {
        private val DEFAULT_SENSITIVE_NAMES = setOf(
            "authorization",
            "cookie",
            "set-cookie",
            "password",
            "passwd",
            "token",
            "access-token",
            "access_token",
            "accessToken",
            "refresh-token",
            "refresh_token",
            "refreshToken",
            "secret",
            "client-secret",
            "client_secret",
            "clientSecret",
            "api-key",
            "api_key",
            "apiKey",
        )
    }
}
