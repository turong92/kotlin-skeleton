package dev.sumin.skeleton.notification.webpush

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * 구독 끝점 검사(SSRF 방어) - 서버가 남이 준 주소로 POST 한다. 통과 조건: `https` + 허용 목록의 호스트 + 포트 443 + userinfo 없음.
 * 보낼 때는 한 번 더 호스트 이름을 풀어 내부 · 사설 주소가 하나라도 있으면 거절한다. 목록의 `http://host:port` 항목은 그 출처 하나만 정확히 열고(검사 건너뜀) 개발 · e2e 전용이다.
 * 거절 이유에는 끝점(구독 주소는 그 자체가 비밀이다)을 싣지 않는다.
 */
class WebPushEndpointPolicy(
    allowedHosts: List<String>,
    private val resolver: (String) -> List<InetAddress> = { host -> InetAddress.getAllByName(host).toList() },
) {
    private sealed interface Entry {
        data class Exact(val host: String) : Entry
        data class Suffix(val suffix: String) : Entry
        data class Origin(val host: String, val port: Int) : Entry
    }

    private val entries: List<Entry> = allowedHosts.mapNotNull(::parseEntry)

    /** 등록 때의 검사(이름만 본다). 괜찮으면 null, 아니면 이유 한 줄 */
    fun check(endpoint: String): String? {
        val uri = parse(endpoint) ?: return "endpoint is not a valid URL"
        val host = uri.host?.lowercase() ?: return "endpoint is not a valid URL"
        if (uri.rawUserInfo != null) return "endpoint must not carry credentials"
        if (entries.any { it is Entry.Origin && it.host == host && it.port == uri.port && uri.scheme == "http" }) return null
        if (uri.scheme != "https") return "endpoint must use https"
        if (uri.port != -1 && uri.port != 443) return "endpoint must use port 443"
        val allowed = entries.any { (it is Entry.Exact && it.host == host) || (it is Entry.Suffix && host.endsWith("." + it.suffix)) }
        return if (allowed) null else "endpoint host is not an allowed push service"
    }

    /** 보낼 때의 검사: [check] + 호스트가 사설 · 내부 주소로 풀리지 않는지. 이름을 못 풀면 [java.net.UnknownHostException] */
    fun checkForSend(endpoint: String): String? {
        check(endpoint)?.let { return it }
        val uri = parse(endpoint)!!
        val host = uri.host.lowercase()
        if (entries.any { it is Entry.Origin && it.host == host && it.port == uri.port && uri.scheme == "http" }) return null
        val addresses = resolver(host)
        if (addresses.isEmpty() || addresses.any(::isInternal)) return "endpoint host resolves to a non-public address"
        return null
    }

    private fun parse(endpoint: String): URI? = try {
        URI(endpoint).takeIf { it.scheme != null && it.host != null }
    } catch (_: Exception) {
        null
    }

    private fun isInternal(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> bytes[0] == 0.toByte() ||                                               // 0.0.0.0/8
                (bytes[0] == 100.toByte() && (bytes[1].toInt() and 0xC0) == 64) ||                    // 100.64.0.0/10 공유 주소 공간
                (bytes[0] == 192.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte())        // 192.0.0.0/24
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC                                    // fc00::/7 고유 로컬
            else -> true
        }
    }

    companion object {
        private val LABEL = "[a-z0-9]([a-z0-9-]*[a-z0-9])?"
        private val HOST = Regex("$LABEL(\\.$LABEL)+")
        private val ORIGIN = Regex("http://($LABEL(\\.$LABEL)*|\\d{1,3}(\\.\\d{1,3}){3}):(\\d{1,5})")

        private fun parseEntry(raw: String): Entry? {
            val entry = raw.trim().lowercase()
            return when {
                entry.startsWith("*.") -> entry.substring(2).takeIf { HOST.matches(it) }?.let(Entry::Suffix)
                entry.startsWith("http://") -> ORIGIN.matchEntire(entry)?.let { Entry.Origin(it.groupValues[1], it.groupValues.last().toInt()) }
                else -> entry.takeIf { HOST.matches(it) }?.let(Entry::Exact)
            }
        }

        /** 무엇에도 맞지 않는(그래서 쓸모없거나 실수인) 목록 항목 */
        fun invalidEntries(allowedHosts: List<String>): List<String> = allowedHosts.filter { parseEntry(it) == null }

        /** 목록의 `http://` 항목 - 개발 · e2e 전용이라 보호 환경에서는 기동 실패 */
        fun httpEntries(allowedHosts: List<String>): List<String> = allowedHosts.filter { it.trim().startsWith("http://", ignoreCase = true) }
    }
}
