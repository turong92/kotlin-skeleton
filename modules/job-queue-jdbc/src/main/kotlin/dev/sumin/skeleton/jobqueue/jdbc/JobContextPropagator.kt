package dev.sumin.skeleton.jobqueue.jdbc

import java.net.URLDecoder
import java.net.URLEncoder
import org.slf4j.MDC

/**
 * 넣는 쪽의 문맥(로그 상관 값)을 작업 줄에 문자열로 실어 보내고, 워커가 돌릴 때 복원하는 고리. 모듈은 문맥이 무엇인지 모른다
 * (페이로드와 따로 `skeleton_jobs.log_context`). 기본 구현은 [MdcJobContextPropagator].
 */
interface JobContextPropagator {
    /** 지금 스레드의 문맥을 문자열로 — 없으면 null (칼럼이 빈다) */
    fun capture(): String?

    /** 저장된 문맥을 지금 스레드에 건다. 닫으면 걸기 전 상태로 돌린다 */
    fun restore(context: String?): AutoCloseable
}

/** MDC 의 [keys] 만 `key=value&…`(퍼센트 인코딩)로 옮긴다 — 다른 키는 건너지 않는다. 키 목록은 `skeleton.job-queue.propagated-mdc-keys` */
class MdcJobContextPropagator(private val keys: List<String>) : JobContextPropagator {
    override fun capture(): String? {
        val kept = mutableListOf<String>()
        var length = 0
        for (key in keys) {
            val value = MDC.get(key)?.takeIf { it.isNotEmpty() } ?: continue
            val pair = "${enc(key)}=${enc(value)}"
            val added = if (kept.isEmpty()) pair.length else pair.length + 1
            if (length + added > MAX_LENGTH) break // 쌍 단위로만 자른다 — 퍼센트 인코딩이 깨지지 않게
            kept += pair
            length += added
        }
        return kept.takeIf { it.isNotEmpty() }?.joinToString("&")
    }

    override fun restore(context: String?): AutoCloseable {
        val previous = keys.associateWith { MDC.get(it) }
        context?.split('&')?.forEach { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) return@forEach
            val key = dec(pair.substring(0, i))
            if (key in keys) MDC.put(key, dec(pair.substring(i + 1)))
        }
        return AutoCloseable {
            previous.forEach { (key, value) -> if (value == null) MDC.remove(key) else MDC.put(key, value) }
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun dec(s: String) = URLDecoder.decode(s, Charsets.UTF_8)

    private companion object {
        /** `log_context varchar(512)` — 넘치면 뒤 쌍부터 통째로 버린다 */
        const val MAX_LENGTH = 512
    }
}
