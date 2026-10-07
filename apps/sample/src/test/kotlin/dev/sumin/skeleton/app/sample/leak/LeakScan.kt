package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import java.net.URLEncoder

/** 응답 하나 — 요청 줄(라벨) · 상태 · 본문 · 헤더. 스캔은 본문과 헤더를 함께 본다 */
data class Captured(val label: String, val status: Int, val body: String, val headers: Map<String, String>)

data class ForbiddenHit(val label: String, val where: String)

/** 응답 조각(글 · 댓글 · 알림 한 건)의 잎 값 하나. [path] 는 점으로 이은 칸 이름(배열 위치 없이), [kind] 는 post | comment | notice … */
data class Leaf(val kind: String, val path: String, val value: Any) {
    val key: String get() = path.substringAfterLast('.')
}

/**
 * 두 작성자의 조각이 공유해도 되는 값 — 이 목록 **밖**의 공통 값은 두 프로필을 잇는 값으로 본다.
 * [strings]: 게시판 코드 · 상태 · 반응 종류 · 알림 종류처럼 모두에게 같은 어휘, 그리고 두 조각이 놓인 같은 글의 제목처럼 시험이 아는 구조 값.
 * [structuralNumbers]: 두 조각이 놓인 스레드의 글 번호 · 최상위 댓글 번호(답글의 parentId · rootId 로 보이는 구조). 개수 · 깊이 칸은 따로 늘 허용한다.
 */
data class LeakAllow(val strings: Set<String>, val structuralNumbers: Set<Long> = emptySet())

/** 작성자 id(공개 id)별로 모은 잎 값. 응답은 `authorId` 를 가진 객체마다 한 조각, 중첩된 답글은 자기 작성자의 조각이다 */
class LeakPieces {
    private val byOwner = linkedMapOf<String, MutableList<Leaf>>()

    fun of(owner: String): List<Leaf> = byOwner[owner].orEmpty()

    fun owners(): Set<String> = byOwner.keys

    fun addJson(text: String) {
        walk(runCatching { JsonPath.parse(text).json<Any>() }.getOrNull())
    }

    /** 이미 읽은 JSON 값(Map · List)에서 */
    fun addNode(node: Any?) = walk(node)

    /** 데이터베이스 행 하나를 같은 모양의 조각으로 (열 이름이 칸 이름) */
    fun addRow(owner: String, kind: String, row: Map<String, Any?>) {
        row.forEach { (column, value) -> leafOf(kind, column, value)?.let { byOwner.getOrPut(owner) { mutableListOf() } += it } }
    }

    private fun walk(node: Any?) {
        when (node) {
            is Map<*, *> -> if (node["authorId"] is String) piece(node) else node.values.forEach { walk(it) }
            is List<*> -> node.forEach { walk(it) }
        }
    }

    private fun piece(node: Map<*, *>) {
        val owner = node["authorId"] as String
        val kind = if (node.containsKey("postId")) "comment" else "post"
        val nested = mutableListOf<Map<*, *>>()
        fun collect(map: Map<*, *>, path: String) {
            map.forEach { (k, v) ->
                val p = if (path.isEmpty()) k.toString() else "$path.$k"
                when (v) {
                    is Map<*, *> -> if (v["authorId"] is String) nested += v else collect(v, p)
                    is List<*> -> v.forEach { e ->
                        when (e) {
                            is Map<*, *> -> if (e["authorId"] is String) nested += e else collect(e, p)
                            else -> leafOf(kind, p, e)?.let { add(owner, it) }
                        }
                    }
                    else -> leafOf(kind, p, v)?.let { add(owner, it) }
                }
            }
        }
        byOwner.getOrPut(owner) { mutableListOf() }
        collect(node, "")
        nested.forEach { piece(it) }
    }

    private fun add(owner: String, leaf: Leaf) { byOwner.getOrPut(owner) { mutableListOf() } += leaf }

    private fun leafOf(kind: String, path: String, value: Any?): Leaf? = when (value) {
        is String -> Leaf(kind, path, value)
        is Number -> Leaf(kind, path, if (value.toDouble() % 1.0 == 0.0) value.toLong() else value.toDouble())
        else -> null   // null · 불리언은 허용 목록(스펙 §12 5)
    }
}

object LeakScan {
    /** 값이 [captured] 의 본문 · 헤더 어디에든 있으면 — 대소문자 무시, URL 인코딩 꼴도 */
    fun forbiddenHits(captured: Collection<Captured>, forbidden: Map<String, String>): List<ForbiddenHit> = captured.flatMap { c ->
        val haystack = (listOf(c.body) + c.headers.map { (k, v) -> "$k: $v" }).joinToString("\n").lowercase()
        forbidden.filter { (_, value) ->
            val v = value.lowercase()
            haystack.contains(v) || haystack.contains(URLEncoder.encode(v, Charsets.UTF_8).lowercase())
        }.map { ForbiddenHit(it.key, c.label) }
    }

    private val COUNTER = Regex("(?i)count|depth|sort_?order|size|page")
    private val FILE_TAIL = Regex("^file(\\.[a-z0-9]{1,8})?$")
    private val OPAQUE_FIXED = setOf("uploads", "o")

    /** 두 조각이 공유하는 값 (허용 목록 밖) — 사람이 읽는 줄. 비어 있어야 한다 */
    fun commonValues(a: List<Leaf>, b: List<Leaf>, allow: LeakAllow): List<String> {
        val out = linkedSetOf<String>()
        val aStrings = a.mapNotNull { it.value as? String }.toSet()
        val bStrings = b.mapNotNull { it.value as? String }.toSet()
        (aStrings intersect bStrings).filter { it !in allow.strings }.forEach { out += "string $it" }
        // 첨부 키 같은 경로 꼴: 마디마다 따로 — 한 마디(uuid.mac)만 같아도 잇는다. 고정 접두사 · 알려진 확장자만 남은 파일 이름은 누구에게나 같다
        fun segments(strings: Set<String>) = strings.filter { '/' in it }.flatMap { it.split('/') }.filter { it.isNotEmpty() }.toSet()
        (segments(aStrings) intersect segments(bStrings)).filter { it !in allow.strings && it !in OPAQUE_FIXED && !FILE_TAIL.matches(it) }.forEach { out += "string $it" }
        fun numbers(leaves: List<Leaf>) = leaves.filter { it.value is Number && !COUNTER.containsMatchIn(it.path) && it.value !in allow.structuralNumbers }.map { it.kind to it.value }.toSet()
        (numbers(a) intersect numbers(b)).forEach { (kind, value) -> out += "number $kind $value" }
        return out.toList()
    }
}
