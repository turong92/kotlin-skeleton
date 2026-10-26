package dev.sumin.skeleton.dashboard.cloudflare

import com.sun.net.httpserver.HttpServer
import dev.sumin.skeleton.common.http.DefaultExternalHttpClient
import dev.sumin.skeleton.common.http.DefaultExternalHttpErrorMapper
import dev.sumin.skeleton.common.http.OutboundHttpProperties
import dev.sumin.skeleton.dashboard.DashboardDailyRow
import dev.sumin.skeleton.dashboard.DashboardDailyStore
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
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

/** 루프백에만 묶는 가짜 Cloudflare GraphQL. 별칭마다 [rows] 의 줄을 답하고 받은 요청을 남긴다 */
internal class FakeCloudflareApi : AutoCloseable {
    data class Row(val dimension: String? = null, val count: Long, val visits: Long, val sampleInterval: Double = 1.0)
    data class Req(val auth: String?, val accountTag: String, val siteTag: String, val from: String, val to: String, val aliases: List<String>)

    val requests: MutableList<Req> = Collections.synchronizedList(mutableListOf())
    val rows = ConcurrentHashMap<String, List<Row>>()
    @Volatile var status = 200

    private val mapper = JsonMapper.builder().build()
    private val dimensionOf = mapOf("hour" to "datetimeHour", "referrer" to "refererHost", "path" to "requestPath", "country" to "countryName", "device" to "deviceType")

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            val body = mapper.readTree(ex.requestBody.readBytes().decodeToString())
            val query = body["query"].asString()
            val aliases = Regex("(\\w+): rumPageloadEventsAdaptiveGroups").findAll(query).map { it.groupValues[1] }.toList()
            val filter = body["variables"]["filter"]
            requests += Req(ex.requestHeaders.getFirst("Authorization"), body["variables"]["accountTag"].asString(), filter["siteTag"].asString(), filter["datetime_geq"].asString(), filter["datetime_lt"].asString(), aliases)
            val account = aliases.associateWith { alias ->
                rows[alias].orEmpty().map { row ->
                    buildMap<String, Any> {
                        put("count", row.count)
                        put("sum", mapOf("visits" to row.visits))
                        put("avg", mapOf("sampleInterval" to row.sampleInterval))
                        dimensionOf[alias]?.let { put("dimensions", mapOf(it to (row.dimension ?: ""))) }
                    }
                }
            }
            val out = mapper.writeValueAsBytes(mapOf("data" to mapOf("viewer" to mapOf("accounts" to listOf(account))), "errors" to null))
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, if (status == 200) out.size.toLong() else -1)
            if (status == 200) ex.responseBody.use { it.write(out) } else ex.close()
        }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
