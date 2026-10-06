package dev.sumin.skeleton.idempotency

import jakarta.servlet.http.HttpServletRequest
import java.security.MessageDigest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

object IdempotencyFingerprint {
    fun calculate(
        request: HttpServletRequest,
        body: ByteArray,
        ignoredBodyFields: Set<String> = emptySet(),
        objectMapper: ObjectMapper? = null,
    ): String {
        val canonicalRequest = buildString {
            append(request.method.uppercase())
            append('\n')
            append(request.requestURI)
            append('\n')
            append(request.queryString.orEmpty())
            append('\n')
            append(request.contentType.orEmpty())
            append('\n')
        }.toByteArray(Charsets.UTF_8)

        return sha256(canonicalRequest + withoutIgnored(body, ignoredBodyFields, objectMapper))
    }

    /** JSON 이면 [ignored] 이름의 필드를 어느 깊이에서든 빼고 다시 쓴다 (필드 순서도 같게). JSON 이 아니면 본문 그대로 */
    private fun withoutIgnored(body: ByteArray, ignored: Set<String>, objectMapper: ObjectMapper?): ByteArray {
        if (ignored.isEmpty() || objectMapper == null || body.isEmpty()) return body
        val tree = try { objectMapper.readTree(body) } catch (_: Exception) { return body }
        return objectMapper.writeValueAsBytes(strip(tree, ignored))
    }

    private fun strip(node: JsonNode, ignored: Set<String>): JsonNode {
        if (node is ObjectNode) {
            val sorted = node.propertyNames().asSequence().filter { it !in ignored }.sorted().toList()
            val out = node.objectNode()
            sorted.forEach { out.set(it, strip(node.get(it), ignored)) }
            return out
        }
        if (node is ArrayNode) {
            val out = node.arrayNode()
            node.forEach { out.add(strip(it, ignored)) }
            return out
        }
        return node
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }
}
