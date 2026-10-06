package dev.sumin.skeleton.legal

import java.time.Instant
import java.time.format.DateTimeParseException
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 앱이 싣는 문서의 위치 — 디렉터리 하나: `manifest.json` 과 `<type>/<version>.<locale>.md`. 위치는 스프링 리소스 문자열이다
 * (`classpath:legal/`, `file:/etc/myapp/legal/`). 경로 조각은 영숫자 · `.` `_` `-` 만 받는다 — 요청이 만든 값으로 디렉터리를 벗어날 수 없게.
 */
class LegalSources(location: String, private val loader: ResourceLoader) {
    private val base = if (location.endsWith("/")) location else "$location/"

    /** `manifest.json` 이 없으면 null — 모듈 예시 문서로 물러설 신호 */
    fun manifest(): List<ManifestVersion>? {
        val resource = loader.getResource(base + MANIFEST)
        if (!resource.exists()) return null
        return parse(resource.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }, MANIFEST)
    }

    fun read(type: String, version: String, locale: String): String? {
        if (listOf(type, version, locale).any { !SEGMENT.matches(it) || it.contains("..") }) return null
        val resource = loader.getResource("$base$type/$version.$locale.md")
        return if (resource.exists()) resource.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) } else null
    }

    companion object {
        const val MANIFEST = "manifest.json"
        private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val json = JsonMapper.builder().build()

        fun parse(text: String, name: String): List<ManifestVersion> {
            fun fail(message: String): Nothing = throw IllegalStateException("skeleton.legal: $name $message")
            val root = try {
                json.readTree(text)
            } catch (e: Exception) {
                fail("is not valid JSON (${e.javaClass.simpleName})")
            }
            val documents = root.path("documents")
            if (!documents.isArray) fail("needs a 'documents' array")
            return documents.values().flatMap { doc ->
                val type = doc.path("type").takeIf { it.isString }?.asString() ?: fail("has a document without a 'type'")
                val versions = doc.path("versions")
                if (!versions.isArray) fail("document '$type' needs a 'versions' array")
                versions.values().map { v -> version(type, v, ::fail) }
            }
        }

        private fun version(type: String, v: JsonNode, fail: (String) -> Nothing): ManifestVersion {
            val version = v.path("version").takeIf { it.isString }?.asString() ?: fail("$type: version needs 'version'")
            val at = "$type $version"
            val statusText = v.path("status").takeIf { it.isString }?.asString() ?: fail("$at: needs 'status' (DRAFT or REVIEWED)")
            val status = DocumentStatus.entries.firstOrNull { it.name == statusText } ?: fail("$at: status '$statusText' must be DRAFT or REVIEWED")
            val locales = v.path("locales").takeIf { it.isArray }?.values()?.map { it.asString() }?.takeIf { it.isNotEmpty() } ?: fail("$at: needs a non-empty 'locales' array")
            val effective = v.path("effectiveFrom").takeIf { it.isString }?.asString()?.let {
                try {
                    Instant.parse(it)
                } catch (e: DateTimeParseException) {
                    fail("$at: effectiveFrom '$it' is not an ISO-8601 instant such as 2026-10-01T00:00:00Z")
                }
            }
            val hashes = v.path("sha256").takeIf { it.isObject }?.properties()?.associate { it.key to it.value.asString() }.orEmpty()
            hashes.filterValues { !SHA256.matches(it) }.keys.forEach { fail("$at: sha256 of $it must be 64 lowercase hex characters") }
            return ManifestVersion(type, version, status, effective, v.path("template").asBoolean(false), locales, hashes)
        }
    }
}
