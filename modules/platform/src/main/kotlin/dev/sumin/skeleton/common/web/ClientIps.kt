package dev.sumin.skeleton.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 원점 앞에 무엇이 있나. `skeleton.web.client-ip.mode`:
 *
 * - **(설정 안 함)**: 지금까지의 동작 — 서블릿 계층이 보고하는 `remoteAddr` 를 그대로 쓴다. `skeleton.web.forwarded-headers.enabled`(기본 켜짐)의
 *   `ForwardedHeaderFilter` 가 `X-Forwarded-For` · `Forwarded: for=` 로 `remoteAddr` 를 **덮어쓰므로, 직접 붙은 누구든 자기 IP 를 고른다**
 *   (한도 키 우회). 프록시 뒤에서는 그나마 실제 클라이언트가 보이므로 기본을 바꾸지 않았다 — 공개 서비스는 아래 중 하나를 명시한다.
 * - [DIRECT]: 앞에 프록시가 없다. 헤더를 하나도 믿지 않고 소켓 피어 주소가 클라이언트.
 * - [PROXY]: 믿는 프록시(같은 호스트의 리버스 프록시 등)가 `X-Forwarded-For` 에 피어 주소를 **덧붙인다**. 오른쪽부터 믿는 홉을 건너뛴 첫 주소.
 *   `CF-Connecting-IP` 는 보지 않는다.
 * - [CLOUDFLARE]: 원점에 **인증돼** Cloudflare(우리 영역)만 닿는다 — Cloudflare Tunnel(원점 포트 비공개) 또는 영역 전용 인증서를 검사하는 프록시.
 *   믿는 프록시에서 온 요청의 `CF-Connecting-IP`. 없거나 틀리면 [PROXY] 규칙.
 */
enum class ClientIpMode { DIRECT, PROXY, CLOUDFLARE }

/** 요청 한 건의 클라이언트 — [ip] 는 감사 · 사람 확인(`remoteip`)용, [limitKey] 는 요청 한도 · 중복 키(IPv6 는 /64) */
data class ClientAddress(val ip: String, val limitKey: String)

/**
 * `request.remoteAddr` 대신 부르는 한 곳 — platform rate limit · redis-rate-limit · idempotency 의 익명 범위가 모두 여기를 지난다.
 *
 * mode 를 정했다면 [ClientIpFilter] 가 `ForwardedHeaderFilter` **앞에서** 한 번 풀어 요청 속성에 둔다 — 그 필터가 `remoteAddr` 와 전달 헤더를
 * 바꾸거나 지우기 전의 소켓 피어 · 헤더가 필요해서다. 규칙:
 *
 * 1. [ClientIpMode.DIRECT] 이거나 피어가 믿는 프록시가 아니면 헤더를 보지 않는다. Cloudflare 범위 주소도 믿지 않는다:
 *    Workers `connect()` · WARP 로 누구든 Cloudflare 주소에서 원점에 붙을 수 있다.
 * 2. [ClientIpMode.CLOUDFLARE] 면 `CF-Connecting-IP` 가 IP 모양일 때 그것.
 * 3. 아니면 `X-Forwarded-For` 를 오른쪽부터 걸으며 믿는 프록시를 건너뛴다. 처음 만난 믿지 않는 주소가 클라이언트.
 *    IP 모양이 아닌 항목에서 멈추고 바로 오른쪽 홉을 쓴다(DNS 조회를 하지 않는다).
 */
class ClientIps(props: WebProperties.ClientIp = WebProperties.ClientIp()) {
    private val mode: ClientIpMode? = props.mode
    private val proxies: List<Cidr> = props.trustedProxies.map(String::trim).filter(String::isNotEmpty).map(Cidr::parse)

    init {
        require(mode == null || mode == ClientIpMode.DIRECT || proxies.isNotEmpty()) {
            "skeleton.web.client-ip.mode=${mode?.name?.lowercase()} needs skeleton.web.client-ip.trusted-proxies"
        }
    }

    /** mode 가 정해졌을 때만 [ClientIpFilter] 가 필요하다 */
    val configured: Boolean get() = mode != null

    fun describe(): String = when (mode) {
        null -> "unset (remoteAddr as the servlet layer reports it; ForwardedHeaderFilter lets any caller choose it)"
        ClientIpMode.DIRECT -> "direct (no forwarding header is trusted)"
        else -> "${mode.name.lowercase()} (trusted proxies ${proxies.joinToString(",")})"
    }

    fun of(request: HttpServletRequest): ClientAddress =
        request.getAttribute(ATTRIBUTE) as? ClientAddress ?: resolve(request)

    /** 요청 속성을 보지 않고 풀어 낸다 — [ClientIpFilter] 가 부른다 */
    fun resolve(request: HttpServletRequest): ClientAddress {
        if (mode == null) return raw(request.remoteAddr)
        val remote = literal(request.remoteAddr) ?: return raw(request.remoteAddr)
        if (mode == ClientIpMode.DIRECT || !trusted(remote)) return address(remote)
        if (mode == ClientIpMode.CLOUDFLARE) literal(request.getHeader(CF_CONNECTING_IP))?.let { return address(it) }
        var last = remote
        val chain = request.getHeaders(FORWARDED_FOR)?.toList().orEmpty().flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)
        for (entry in chain.asReversed()) {
            val hop = literal(entry) ?: break
            if (!trusted(hop)) return address(hop)
            last = hop
        }
        return address(last)
    }

    private fun trusted(ip: InetAddress) = proxies.any { it.contains(ip) }

    /** 모드가 없거나 주소 글자가 아닐 때 — IP 글자면 정규 표기로, 아니면 그대로(최대 64자) */
    private fun raw(value: String?): ClientAddress = ((literal(value)?.let(::canonical)) ?: (value ?: "unknown").take(64)).let { ClientAddress(it, it) }

    private fun address(ip: InetAddress): ClientAddress {
        val text = canonical(ip)
        val key = if (ip is Inet6Address) canonical(InetAddress.getByAddress(ip.address.copyOf(8) + ByteArray(8))) + "/64" else text
        return ClientAddress(text, key)
    }

    private class Cidr(val network: ByteArray, val prefix: Int, private val text: String) {
        fun contains(ip: InetAddress): Boolean {
            val bytes = ip.address
            if (bytes.size != network.size) return false
            var bits = prefix
            for (i in bytes.indices) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }

        override fun toString() = text

        companion object {
            fun parse(value: String): Cidr {
                val (host, bits) = value.split('/', limit = 2).let { it[0] to it.getOrNull(1) }
                val ip = literal(host) ?: throw IllegalArgumentException("skeleton.web.client-ip.trusted-proxies entry is not an IP: '${value.take(60)}'")
                val max = ip.address.size * 8
                val prefix = bits?.toIntOrNull() ?: if (bits == null) max else -1
                require(prefix in 0..max) { "skeleton.web.client-ip.trusted-proxies prefix length is wrong: '${value.take(60)}'" }
                return Cidr(ip.address, prefix, value)
            }
        }
    }

    companion object {
        /** [ClientIpFilter] 가 풀어 낸 [ClientAddress] 를 두는 요청 속성 */
        const val ATTRIBUTE = "dev.sumin.skeleton.clientAddress"
        const val FORWARDED_FOR = "X-Forwarded-For"
        const val CF_CONNECTING_IP = "CF-Connecting-IP"
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val IPV4_PORT = Regex("^(\\d{1,3}(\\.\\d{1,3}){3}):\\d{1,5}$")
        private val IPV6 = Regex("^[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*$")

        /**
         * 사람이 읽는 정규 표기 (RFC 5952) — IPv4 는 점 표기, IPv6 는 소문자 · 앞자리 0 없이 · **가장 긴** 0 그룹 연속(둘 이상, 같으면 앞쪽)을 `::` 로.
         * 서블릿 컨테이너(Tomcat)가 주는 `0:0:0:0:0:0:0:1` 같은 풀어 쓴 꼴이 세션 목록 · 한도 키 · 감사 기록에 그대로 나가지 않게 — 저장 · 응답 · 한도가 **같은 표기**를 쓰도록 이 한 곳에서 만든다.
         */
        fun canonical(ip: InetAddress): String {
            if (ip !is Inet6Address) return ip.hostAddress
            val groups = IntArray(8) { ((ip.address[it * 2].toInt() and 0xFF) shl 8) or (ip.address[it * 2 + 1].toInt() and 0xFF) }
            var bestStart = -1; var bestLen = 0
            var i = 0
            while (i < 8) {
                if (groups[i] != 0) { i++; continue }
                var j = i
                while (j < 8 && groups[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            }
            if (bestLen < 2) return groups.joinToString(":") { Integer.toHexString(it) }
            val head = groups.take(bestStart).joinToString(":") { Integer.toHexString(it) }
            val tail = groups.drop(bestStart + bestLen).joinToString(":") { Integer.toHexString(it) }
            return "$head::$tail"
        }

        /** IP 글자만 주소로 — 이름이면 null(조회하지 않는다). `1.2.3.4:80` · `[::1]:443` 의 포트는 뗀다 */
        fun literal(value: String?): InetAddress? {
            var text = value?.trim().orEmpty()
            if (text.isEmpty() || text.length > 64) return null
            if (text.startsWith("[")) text = text.substringAfter('[').substringBefore(']')
            IPV4_PORT.matchEntire(text)?.let { text = it.groupValues[1] }
            val ok = IPV4.matches(text) && text.split('.').all { it.toInt() <= 255 } || IPV6.matches(text)
            if (!ok) return null
            return runCatching { InetAddress.getByName(text) }.getOrNull()?.takeIf { it is Inet4Address || it is Inet6Address }
        }
    }
}

/** `ForwardedHeaderFilter` 가 `remoteAddr` · 전달 헤더를 바꾸기 전에 클라이언트를 풀어 요청 속성([ClientIps.ATTRIBUTE])에 둔다 */
class ClientIpFilter(private val clientIps: ClientIps) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        request.setAttribute(ClientIps.ATTRIBUTE, clientIps.resolve(request))
        filterChain.doFilter(request, response)
    }
}

