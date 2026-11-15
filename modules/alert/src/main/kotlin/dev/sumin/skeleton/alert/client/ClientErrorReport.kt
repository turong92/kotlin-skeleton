package dev.sumin.skeleton.alert.client

import dev.sumin.skeleton.common.ApplicationException
import dev.sumin.skeleton.common.ErrorCode
import dev.sumin.skeleton.common.logging.LogMasker
import org.springframework.http.HttpStatus
import tools.jackson.databind.json.JsonMapper

/**
 * 받아서 **걸러 낸** 클라이언트 오류 하나. 이 모양만 로그 · 경보로 나간다 — 받은 본문을 그대로 쓰는 곳은 없다.
 * [clientTraceId] 는 그 기기가 **마지막으로 보낸 API 요청**의 traceId (서버 로그에서 같은 값으로 찾는다, 이 보고 요청 자신의 traceId 와는 다르다).
 * [count] 는 기기에서 같은 오류를 접어 보낸 횟수.
 */
data class ClientErrorReport(
    val platform: String?,
    val source: String?,
    val name: String?,
    val message: String,
    val stack: String?,
    val componentStack: String?,
    val route: String?,
    val release: String?,
    val clientTraceId: String?,
    val count: Int,
    val userAgent: String?,
)

enum class ClientErrorCode(override val code: String, override val status: HttpStatus, override val title: String) : ErrorCode {
    INVALID("CLIENT_ERROR.INVALID", HttpStatus.BAD_REQUEST, "Not a client error report"),
    TOO_LARGE("CLIENT_ERROR.TOO_LARGE", HttpStatus.CONTENT_TOO_LARGE, "Client error report is too large"),
    TOO_MANY("CLIENT_ERROR.TOO_MANY", HttpStatus.TOO_MANY_REQUESTS, "Too many client error reports"),
}

class ClientErrorException(code: ClientErrorCode, data: Any? = null) : ApplicationException(code.title, code, data = data)

/** 본문(JSON) → [ClientErrorReport]. 틀린 형의 값은 없는 것으로 보고, 모르는 필드는 버린다. 필드마다 길이 상한, 토큰 · 이메일 · URL 쿼리 제거, 줄바꿈으로 로그 줄을 못 위조하게 한 줄로 */
object ClientErrorSanitizer {
    private val mapper = JsonMapper.builder().build()
    // 제어 문자 + C1 + 방향 조작 문자(글을 뒤집어 보이게 하는 것)
    private const val INVISIBLE = "\\u0080-\\u009F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069"
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F$INVISIBLE]")
    private val CONTROL_KEEP_NEWLINE = Regex("[\\u0000-\\u0009\\u000B-\\u001F\\u007F$INVISIBLE]")
    private val URL_QUERY = Regex("(https?://[^\\s?#\"')]+)[?#][^\\s\"')]*")
    private val PATH_QUERY = Regex("((?:^|[\\s\"'(])/[^\\s?#\"')]*)[?#][^\\s\"')]*")
    private val EMAIL = Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,}")
    private val TRACE_ID = Regex("^[0-9a-f]{32}$")

    const val PLATFORM_MAX = 32
    const val NAME_MAX = 100
    const val MESSAGE_MAX = 500
    const val STACK_MAX = 4000
    const val COMPONENT_STACK_MAX = 1500
    const val ROUTE_MAX = 200
    const val RELEASE_MAX = 64
    const val USER_AGENT_MAX = 200
    const val COUNT_MAX = 1000

    fun parse(body: ByteArray, userAgent: String?): ClientErrorReport {
        val map = try {
            mapper.readValue(body, Map::class.java)
        } catch (e: Exception) {
            null
        } ?: throw ClientErrorException(ClientErrorCode.INVALID)
        fun text(key: String) = map[key] as? String
        val message = line(text("message"), MESSAGE_MAX) ?: throw ClientErrorException(ClientErrorCode.INVALID)
        return ClientErrorReport(
            platform = line(text("platform"), PLATFORM_MAX),
            source = line(text("source"), PLATFORM_MAX),
            name = line(text("name"), NAME_MAX),
            message = message,
            stack = block(text("stack"), STACK_MAX),
            componentStack = block(text("componentStack"), COMPONENT_STACK_MAX),
            route = line(text("route")?.substringBefore('?')?.substringBefore('#'), ROUTE_MAX),
            release = line(text("release"), RELEASE_MAX),
            clientTraceId = text("traceId")?.lowercase()?.takeIf { TRACE_ID.matches(it) && it.any { c -> c != '0' } },
            count = (map["count"] as? Number)?.toInt()?.coerceIn(1, COUNT_MAX) ?: 1,
            userAgent = line(userAgent, USER_AGENT_MAX),
        )
    }

    /** 한 줄 글 — 제어 문자(줄바꿈 포함)는 공백, 비면 null */
    private fun line(raw: String?, max: Int): String? {
        raw ?: return null
        val cleaned = scrub(CONTROL.replace(raw.take(max * 2), " ")).trim().take(max)
        return cleaned.ifEmpty { null }
    }

    /** 여러 줄 글(스택) — 줄바꿈은 두되 그 밖의 제어 문자는 공백 */
    private fun block(raw: String?, max: Int): String? {
        raw ?: return null
        val cleaned = scrub(CONTROL_KEEP_NEWLINE.replace(raw.replace("\r", "").take(max * 2), " ")).trim().take(max)
        return cleaned.ifEmpty { null }
    }

    private fun scrub(text: String): String {
        var out = URL_QUERY.replace(text) { it.groupValues[1] }
        out = PATH_QUERY.replace(out) { it.groupValues[1] }
        out = LogMasker.current.mask(out)
        return EMAIL.replace(out, "<email>")
    }
}
