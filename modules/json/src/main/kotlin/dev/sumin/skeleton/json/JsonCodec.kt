package dev.sumin.skeleton.json

import kotlin.reflect.KClass
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.kotlinModule

interface JsonCodec {
    fun parse(raw: String): JsonDocument

    fun stringify(document: JsonDocument): String

    fun canonicalString(document: JsonDocument): String

    fun <T : Any> toDocument(value: T): JsonDocument

    fun <T : Any> fromDocument(document: JsonDocument, targetClass: KClass<T>): T

    fun <T : Any> fromDocument(document: JsonDocument, targetClass: Class<T>): T =
        fromDocument(document, targetClass.kotlin)
}

class JacksonJsonCodec(
    private val objectMapper: ObjectMapper = defaultObjectMapper(),
) : JsonCodec {
    override fun parse(raw: String): JsonDocument =
        JsonDocument.from(objectMapper.readTree(raw))

    override fun stringify(document: JsonDocument): String =
        objectMapper.writeValueAsString(document.node)

    override fun canonicalString(document: JsonDocument): String =
        objectMapper.writeValueAsString(canonicalNode(document.node))

    override fun <T : Any> toDocument(value: T): JsonDocument =
        JsonDocument.from(objectMapper.valueToTree(value))

    override fun <T : Any> fromDocument(document: JsonDocument, targetClass: KClass<T>): T =
        objectMapper.treeToValue(document.node, targetClass.java)

    private fun canonicalNode(node: JsonNode): JsonNode =
        when {
            node.isObject -> canonicalObject(node.asObject())
            node.isArray -> canonicalArray(node.asArray())
            else -> node.deepCopy()
        }

    private fun canonicalObject(node: ObjectNode): ObjectNode =
        objectMapper.createObjectNode().also { sorted ->
            node.properties()
                .sortedBy { it.key }
                .forEach { (name, value) -> sorted.set(name, canonicalNode(value)) }
        }

    private fun canonicalArray(node: ArrayNode): ArrayNode =
        objectMapper.createArrayNode().also { sorted ->
            node.values().forEach { value -> sorted.add(canonicalNode(value)) }
        }

    companion object {
        fun defaultObjectMapper(): ObjectMapper =
            JsonMapper.builder()
                .addModule(kotlinModule())
                .build()
    }
}
