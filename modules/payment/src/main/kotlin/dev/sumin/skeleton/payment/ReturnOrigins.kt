package dev.sumin.skeleton.payment

import java.net.URI

/**
 * 결제사가 구매자를 돌려보낼 주소를 **결제를 시작한 화면의 origin** 으로 맞춘다(카카오페이 · 네이버페이 직접 연동). 로그인 저장소가 origin 마다 따로라, 다른 origin 으로 돌아오면 로그인이 풀린다.
 * 열린 리다이렉트가 되지 않게 **허용 목록**에 든 origin 만 받는다 - 목록이 비었거나 목록 밖이거나 origin 모양이 아니면 설정의 주소를 그대로 쓴다.
 */
object ReturnOrigins {
    /** [configured] 의 경로 · 질의는 두고 scheme · host · port 만 [requested] 로 바꾼다. */
    fun resolve(configured: String, requested: String?, allowed: Set<String>): String {
        val origin = originOf(requested) ?: return configured
        if (allowed.none { originOf(it) == origin }) return configured
        val uri = runCatching { URI.create(configured) }.getOrNull() ?: return configured
        if (uri.rawAuthority == null) return configured
        return origin + uri.rawPath.orEmpty() + (uri.rawQuery?.let { "?$it" } ?: "")
    }

    /** `scheme://host[:port]` 만 받는다(경로 · 질의 · 조각 · 사용자 정보 · 공백 없음; 허용 목록의 끝 `/` 하나는 눈감는다). 소문자로 맞춰 돌려준다. */
    internal fun originOf(raw: String?): String? {
        val text = raw?.removeSuffix("/")?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) && '\\' !in it } ?: return null
        val uri = runCatching { URI.create(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = uri.host?.lowercase() ?: return null
        val clean = uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null && uri.userInfo == null
        if (!clean) return null
        return "$scheme://$host" + (if (uri.port >= 0) ":${uri.port}" else "")
    }
}
