package dev.sumin.skeleton.app.sample.leak

import com.jayway.jsonpath.JsonPath
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders

/** MockMvc 호출 하나하나를 [Captured] 로 남기는 얇은 클라이언트 — 본문과 헤더 전부. 스캔은 [since] 로 한 단계 동안 쌓인 것만 본다 */
class LeakHttp(private val mvc: MockMvc) {
    private val log = CopyOnWriteArrayList<Captured>()

    fun mark(): Int = log.size

    fun since(mark: Int): List<Captured> = log.drop(mark)

    fun call(method: HttpMethod, path: String, token: String? = null, body: String? = null, idem: Boolean = false, record: Boolean = true): Captured {
        val request = MockMvcRequestBuilders.request(method, path)
        token?.let { request.header("Authorization", "Bearer $it") }
        if (idem) request.header("Idempotency-Key", UUID.randomUUID().toString())
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body)
        val response = mvc.perform(request).andReturn().response
        val headers = response.headerNames.associateWith { name -> response.getHeaders(name).joinToString(",") }
        return Captured("$method $path", response.status, response.contentAsString, headers).also { if (record) log += it }
    }

    fun get(path: String, token: String? = null, record: Boolean = true) = call(HttpMethod.GET, path, token, record = record)

    fun post(path: String, token: String?, body: String, record: Boolean = true) = call(HttpMethod.POST, path, token, body, idem = true, record = record)

    fun put(path: String, token: String?, body: String) = call(HttpMethod.PUT, path, token, body)

    fun patch(path: String, token: String?, body: String) = call(HttpMethod.PATCH, path, token, body)

    fun delete(path: String, token: String?) = call(HttpMethod.DELETE, path, token)
}

fun <T> Captured.read(path: String): T = JsonPath.read(body, path)

fun Captured.ok(): Captured = also { check(status in 200..299) { "$label -> $status $body" } }

fun json(text: String): String = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
