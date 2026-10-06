package dev.sumin.skeleton.legal

import java.security.MessageDigest
import java.util.HexFormat

/**
 * 문서 원문(마크다운)을 다루는 순수 함수들 — 정규화 · 해시 · 제목 · `{{자리표시}}` 채우기 · 안전한 부분집합 검사.
 *
 * 해시는 **원문**(자리표시가 채워지기 전)의 정규화본에 건다. 정규화: BOM 제거 → 줄바꿈 `\n` → 줄 끝 공백 제거 → 끝 빈 줄 제거.
 * 편집기의 줄바꿈 · 끝 공백 차이로 해시가 흔들리지 않고, 글자 하나가 바뀌면 반드시 바뀐다.
 */
object LegalText {
    data class Rendered(val text: String, val missing: Set<String>)

    private val PLACEHOLDER = Regex("\\{\\{([A-Za-z][A-Za-z0-9_-]*)}}")
    private val CODE_SPAN = Regex("`[^`]*`")
    private val HTML = Regex("<[A-Za-z/!?]")
    private val INLINE_LINK = Regex("\\]\\(\\s*([^)\\s]*)[^)]*\\)")
    private val REFERENCE_LINK = Regex("^\\s*\\[[^\\]]+]:\\s*(\\S+)")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    fun normalise(source: String): String =
        source.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
            .lines().joinToString("\n") { it.trimEnd(' ', '\t') }.trimEnd('\n')

    fun sha256(source: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalise(source).toByteArray(Charsets.UTF_8)))

    /** 첫 번째 `# ` 제목 — 없으면 null */
    fun title(source: String): String? =
        normalise(source).lines().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()?.takeIf { it.isNotEmpty() }

    fun placeholders(source: String): Set<String> = PLACEHOLDER.findAll(source).map { it.groupValues[1] }.toSet()

    /** 한 번만 채운다 — 값 안의 `{{…}}` 는 다시 풀지 않는다. 없는 사실은 그대로 보이게 두고 [Rendered.missing] 에 적는다 */
    fun render(source: String, facts: Map<String, String>): Rendered {
        val missing = linkedSetOf<String>()
        val text = PLACEHOLDER.replace(source) { match ->
            val value = facts[match.groupValues[1]]
            if (value == null) {
                missing += match.groupValues[1]
                match.value
            } else {
                value
            }
        }
        return Rendered(text, missing)
    }

    /**
     * 허용하는 부분집합이 아닌 것들 — 줄 번호와 함께. 원시 HTML · 이미지 · `https:` `mailto:` 상대(`/x`) 앵커(`#x`) 밖의 링크 목적지.
     * 프런트가 어떤 CommonMark 렌더러를 쓰든(원시 HTML 을 끈 채) 같은 모양이 나오도록 서버가 먼저 거른다.
     */
    fun problems(source: String): List<String> {
        val problems = mutableListOf<String>()
        normalise(source).lines().forEachIndexed { index, raw ->
            val line = CODE_SPAN.replace(raw, "")
            val at = "line ${index + 1}"
            if (HTML.containsMatchIn(line)) problems += "$at: raw HTML is not allowed"
            if (line.contains("![")) problems += "$at: images are not allowed"
            val destinations = INLINE_LINK.findAll(line).map { it.groupValues[1] }.toList() +
                listOfNotNull(REFERENCE_LINK.find(line)?.groupValues?.get(1))
            destinations.forEach { dest -> destinationProblem(dest)?.let { problems += "$at: $it" } }
        }
        return problems
    }

    private fun destinationProblem(destination: String): String? {
        val dest = destination.trim()
        if (dest.isEmpty() || dest.startsWith("#")) return null
        if (dest.startsWith("//")) return "scheme-relative link destination is not allowed"
        if (dest.startsWith("/")) return null
        val scheme = SCHEME.find(dest)?.groupValues?.get(1)?.lowercase()
            ?: return if (dest.contains(':')) "link destination is not allowed" else null
        return if (scheme == "https" || scheme == "mailto") null else "link destination scheme '$scheme' is not allowed"
    }
}
