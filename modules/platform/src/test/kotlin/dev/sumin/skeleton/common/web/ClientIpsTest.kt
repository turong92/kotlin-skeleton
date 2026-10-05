package dev.sumin.skeleton.common.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.mock.web.MockHttpServletRequest

/**
 * Real client IP. With a mode set, forwarding headers are only read from a trusted proxy peer. A Cloudflare-range address is
 * not trusted by itself: anyone can reach the origin from a Cloudflare address (Workers `connect()`, WARP).
 */
class ClientIpsTest {
    private val direct = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT))
    private val proxy = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.PROXY))
    private val cloudflare = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.CLOUDFLARE))

    private fun request(remote: String, xff: String? = null, cf: String? = null) = MockHttpServletRequest().apply {
        remoteAddr = remote
        xff?.let { addHeader("X-Forwarded-For", it) }
        cf?.let { addHeader("CF-Connecting-IP", it) }
    }

    @Test
    fun `direct reads no header, even from loopback or a Cloudflare edge`() {
        assertEquals("203.0.113.9", direct.of(request("203.0.113.9", xff = "1.2.3.4", cf = "5.6.7.8")).ip)
        assertEquals("127.0.0.1", direct.of(request("127.0.0.1", xff = "1.2.3.4", cf = "5.6.7.8")).ip)
        assertEquals("172.70.1.2", direct.of(request("172.70.1.2", xff = "1.2.3.4", cf = "5.6.7.8")).ip)
    }

    @Test
    fun `unset mode is today's behaviour - the address the servlet layer reports, headers are not interpreted here`() {
        val legacy = ClientIps(WebProperties.ClientIp())
        assertEquals("203.0.113.9", legacy.of(request("203.0.113.9", xff = "1.2.3.4")).ip)
        assertEquals("127.0.0.1", legacy.of(request("127.0.0.1", xff = "1.2.3.4", cf = "5.6.7.8")).ip)
    }

    @Test
    fun `a Cloudflare-range peer that picks its own CF-Connecting-IP is not believed in any mode`() {
        listOf(direct, proxy, cloudflare).forEach { ips ->
            val client = ips.of(request("172.70.1.2", xff = "5.6.7.8", cf = "5.6.7.8"))
            assertEquals("172.70.1.2", client.ip)
            assertEquals("172.70.1.2", client.limitKey)
        }
    }

    @Test
    fun `cloudflare mode takes CF-Connecting-IP from a trusted proxy peer`() {
        assertEquals("198.51.100.20", cloudflare.of(request("127.0.0.1", xff = "9.9.9.9, 198.51.100.20", cf = "198.51.100.20")).ip)
        assertEquals("2001:db8:0:0:0:0:0:5", cloudflare.of(request("::1", cf = "2001:db8::5")).ip)
    }

    @Test
    fun `cloudflare mode falls back to the forwarded chain when the header is missing or garbage, never to a client-filled left value`() {
        assertEquals("172.70.1.2", cloudflare.of(request("127.0.0.1", xff = "9.9.9.9, 172.70.1.2")).ip)
        assertEquals("172.70.1.2", cloudflare.of(request("127.0.0.1", xff = "9.9.9.9, 172.70.1.2", cf = "garbage")).ip)
        assertEquals("127.0.0.1", cloudflare.of(request("127.0.0.1")).ip)
    }

    @Test
    fun `proxy mode ignores CF-Connecting-IP and walks X-Forwarded-For right to left over trusted hops`() {
        assertEquals("198.51.100.20", proxy.of(request("127.0.0.1", xff = "198.51.100.20", cf = "5.6.7.8")).ip)
        assertEquals("198.51.100.20", proxy.of(request("127.0.0.1", xff = "1.2.3.4, 198.51.100.20")).ip)
        assertEquals("198.51.100.20", proxy.of(request("127.0.0.1", xff = "1.2.3.4, 198.51.100.20, 127.0.0.2")).ip)
        assertEquals("162.158.4.5", proxy.of(request("127.0.0.1", xff = "9.9.9.9, 198.51.100.20, 162.158.4.5", cf = "198.51.100.20")).ip)
    }

    @Test
    fun `an entry that is not an IP stops the walk, DNS names are never resolved`() {
        assertEquals("127.0.0.1", proxy.of(request("127.0.0.1", xff = "evil.example.com")).ip)
        assertEquals("127.0.0.1", proxy.of(request("127.0.0.1", xff = "unknown")).ip)
        assertEquals("127.0.0.2", proxy.of(request("127.0.0.1", xff = "1.2.3.4, not-an-ip, 127.0.0.2")).ip)
    }

    @Test
    fun `ports and brackets are stripped`() {
        assertEquals("198.51.100.20", proxy.of(request("127.0.0.1", xff = "198.51.100.20:5555")).ip)
        assertEquals("2001:db8:0:0:0:0:0:1", proxy.of(request("127.0.0.1", xff = "[2001:db8::1]:443")).ip)
    }

    @Test
    fun `the IPv6 limit key is the 64-bit prefix, so rotating addresses is still one client`() {
        val a = direct.of(request("2001:db8:1:2:aaaa::1"))
        val b = direct.of(request("2001:db8:1:2:bbbb::9"))
        assertEquals(a.limitKey, b.limitKey)
        assertEquals("2001:db8:1:2:0:0:0:0/64", a.limitKey)
        assertEquals("2001:db8:1:2:aaaa:0:0:1", a.ip)
    }

    @Test
    fun `trusted proxy CIDRs replace the loopback default`() {
        val bridge = ClientIps(WebProperties.ClientIp(mode = ClientIpMode.CLOUDFLARE, trustedProxies = listOf("172.17.0.1/32")))
        assertEquals("198.51.100.20", bridge.of(request("172.17.0.1", cf = "198.51.100.20")).ip)
        assertEquals("127.0.0.1", bridge.of(request("127.0.0.1", cf = "198.51.100.20")).ip)
    }

    @Test
    fun `bad CIDRs and a header mode without trusted proxies fail at startup`() {
        assertFailsWith<IllegalArgumentException> { ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT, trustedProxies = listOf("10.0.0.0/99"))) }
        assertFailsWith<IllegalArgumentException> { ClientIps(WebProperties.ClientIp(mode = ClientIpMode.DIRECT, trustedProxies = listOf("proxy.local"))) }
        assertFailsWith<IllegalArgumentException> { ClientIps(WebProperties.ClientIp(mode = ClientIpMode.PROXY, trustedProxies = listOf(" "))) }
        assertFailsWith<IllegalArgumentException> { ClientIps(WebProperties.ClientIp(mode = ClientIpMode.CLOUDFLARE, trustedProxies = emptyList())) }
    }
}
