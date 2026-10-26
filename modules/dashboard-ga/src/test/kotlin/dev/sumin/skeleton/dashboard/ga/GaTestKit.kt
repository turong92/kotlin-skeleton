package dev.sumin.skeleton.dashboard.ga

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import dev.sumin.skeleton.dashboard.DashboardDailyStore
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper

internal val NOW: Instant = Instant.parse("2026-10-27T03:00:00Z")   // 서울 12:00 — 오늘 = 2026-10-27

internal fun httpClient() = DefaultExternalHttpClient(
    webClientBuilder = WebClient.builder(),
    properties = OutboundHttpProperties(defaultConnectTimeout = Duration.ofSeconds(30), defaultResponseTimeout = Duration.ofSeconds(30)),
    defaultErrorMapper = DefaultExternalHttpErrorMapper(),
    customizers = emptyList(),
)

/** 키가 (day, source, kind, dimension) 인 메모리 표 — 저장소 포트의 upsert 규칙(같은 키는 덮어쓴다) 그대로 */
internal class MemoryStore : DashboardDailyStore {
    val rows = ConcurrentHashMap<List<Any>, DashboardDailyRow>()
    override fun replace(day: LocalDate, source: String, kinds: Collection<String>, rows: List<DashboardDailyRow>) {
        val replaced = kinds + rows.map { it.kind }
        this.rows.keys.removeIf { it[0] == day && it[1] == source && it[2] in replaced }
        rows.forEach { this.rows[listOf(it.day, it.source, it.kind, it.dimension)] = it }
    }
    override fun read(source: String, kind: String, fromDay: LocalDate, toDay: LocalDate) =
        rows.values.filter { it.source == source && it.kind == kind && it.day in fromDay..toDay }.sortedWith(compareBy({ it.day }, { it.dimension }))
    override fun lastIngested(source: String): Instant? = if (rows.isEmpty()) null else NOW
}

/** 시험용 서비스 계정 — 키 쌍은 만들 때마다 새로, 공개 키는 가짜 GA 가 JWT 서명을 확인하는 데 쓴다. [credentials] 는 키 JSON 의 base64 */
internal class TestServiceAccount(val email: String) {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val publicKey: PublicKey = pair.public
    val privatePem: String = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
    val credentials: String = Base64.getEncoder().encodeToString(
        JsonMapper.builder().build().writeValueAsBytes(mapOf("type" to "service_account", "client_email" to email, "private_key" to privatePem)),
    )
}

/** 루프백에만 묶는 가짜 Google: 토큰 주소(JWT 서명 확인)와 Data API 두 보고서. 받은 요청을 남긴다 */
internal class FakeGaApi : AutoCloseable {
    data class Req(val method: String, val property: String, val auth: String?, val body: String)
    data class Out(val dimensions: List<String> = emptyList(), val metrics: List<Long>)

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    val tokenRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())   // 서명이 맞았던 JWT 의 iss
    /** `report:<차원,…>` 또는 `realtime:<차원,…>` → 줄 */
    val reports = ConcurrentHashMap<String, List<Out>>()
    val accounts = ConcurrentHashMap<String, PublicKey>()
    @Volatile var status = 200

    private val mapper = JsonMapper.builder().build()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/token") { ex ->
            val form = ex.requestBody.readBytes().decodeToString().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val parts = form["assertion"].orEmpty().split('.')
            val iss = runCatching { mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))["iss"].asString() }.getOrNull()
            val verified = iss != null && accounts[iss]?.let { key ->
                Signature.getInstance("SHA256withRSA").apply { initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray()) }.verify(Base64.getUrlDecoder().decode(parts[2]))
            } == true
            if (!verified) { ex.sendResponseHeaders(401, -1); ex.close(); return@createContext }
            tokenRequests += iss!!
            respond(ex, 200, mapper.writeValueAsBytes(mapOf("access_token" to "tok-$iss", "expires_in" to 3600)))
        }
        createContext("/v1beta/properties/") { ex ->
            val tail = ex.requestURI.path.removePrefix("/v1beta/properties/")
            val property = tail.substringBefore(':')
            val method = tail.substringAfter(':')
            val text = ex.requestBody.readBytes().decodeToString()
            requests += Req(method, property, ex.requestHeaders.getFirst("Authorization"), text)
            val dimensions = mapper.readTree(text)["dimensions"]?.toList().orEmpty().joinToString(",") { it["name"].asString() }
            val kind = if (method == "runRealtimeReport") "realtime" else "report"
            val rows = reports["$kind:$dimensions"].orEmpty().map { out ->
                mapOf("dimensionValues" to out.dimensions.map { mapOf("value" to it) }, "metricValues" to out.metrics.map { mapOf("value" to it.toString()) })
            }
            respond(ex, status, mapper.writeValueAsBytes(mapOf("rows" to rows, "rowCount" to rows.size)))
        }
        start()
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, code: Int, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, if (code == 200) body.size.toLong() else -1)
        if (code == 200) ex.responseBody.use { it.write(body) } else ex.close()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"
    val tokenUrl: String get() = "$baseUrl/token"

    fun register(account: TestServiceAccount) { accounts[account.email] = account.publicKey }

    override fun close() = server.stop(0)
}
