package dev.sumin.skeleton.json

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

class JsonDocument private constructor(
    val node: JsonNode,
) {
    init {
        require(!node.isMissingNode) { "JSON document must not be a missing node." }
    }

    fun has(pointer: String): Boolean =
        !node.at(pointer).isMissingNode

    fun requiredAt(pointer: String): JsonNode {
        val value = node.at(pointer)
        require(!value.isMissingNode) { "JSON pointer '$pointer' is missing." }
        return value
    }

    fun textAt(pointer: String): String? {
        val value = node.at(pointer)
        return if (value.isMissingNode || value.isNull) null else value.asString()
    }

    @JsonValue
    fun jsonValue(): JsonNode =
        node

    override fun equals(other: Any?): Boolean =
        other is JsonDocument && node == other.node

    override fun hashCode(): Int =
        node.hashCode()

    override fun toString(): String =
        node.toString()

    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun from(node: JsonNode): JsonDocument =
            JsonDocument(node.deepCopy())
    }
}

data class VersionedJsonDocument(
    val type: String,
    val version: Int,
    val payload: JsonDocument,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(type.isNotBlank()) { "JSON payload type must not be blank." }
        require(version > 0) { "JSON payload version must be positive." }
        require(metadata.keys.none { it.isBlank() }) { "JSON metadata keys must not be blank." }
    }
}
