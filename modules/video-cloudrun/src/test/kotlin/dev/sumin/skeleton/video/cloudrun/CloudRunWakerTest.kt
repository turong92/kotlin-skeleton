package dev.sumin.skeleton.video.cloudrun

import dev.sumin.skeleton.video.VideoWakeException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 구글에 실제로 보내는 요청의 모양 - 가짜 서버(진짜 소켓)로만 확인한다. 실제 구글은 부르지 않는다 */
class CloudRunWakerTest {
    private val account = TestServiceAccount()
    private val google = FakeGoogleServer(account)
    private val clock = TestClock()

    private fun props(json: String = account.json()) = CloudRunProperties(
        enabled = true, project = "demo-project", region = "asia-northeast3", job = "video-worker", credentialsJson = json,
        apiBase = google.base, tokenUri = "${google.base}/token",
    )

    @AfterTest
    fun stop() = google.close()

    @Test
    fun `runs exactly the configured job with a bearer token from a JWT signed by the service account`() {
        val ticket = CloudRunWaker(props(), clock).wake()

        assertEquals("projects/p/locations/r/jobs/j/executions/e1", ticket.execution)
        val run = google.runRequests.single()
        assertEquals("/v2/projects/demo-project/locations/asia-northeast3/jobs/video-worker:run:POST", run.path)
        assertEquals("Bearer ya29.token-1", run.authorization)
        assertEquals("{}", run.body)   // 덮어쓰기(overrides) 없음 - 작업 정의 그대로
        val form = google.tokenRequests.single().body.split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        val claims = account.verifyAssertion(form.getValue("assertion"))
        assertEquals(account.clientEmail, claims["iss"].asString())
        assertEquals("https://www.googleapis.com/auth/cloud-platform", claims["scope"].asString())
        assertEquals("${google.base}/token", claims["aud"].asString())   // 키 파일의 token_uri 가 아니라 설정의 주소
    }

    @Test
    fun `reuses the access token until shortly before it expires`() {
        val waker = CloudRunWaker(props(), clock)
        waker.wake()
        clock.advance(600)
        waker.wake()
        assertEquals(1, google.tokenRequests.size)
        clock.advance(3000)   // 만료 2분 전을 지났다
        waker.wake()
        assertEquals(2, google.tokenRequests.size)
        assertEquals("Bearer ya29.token-2", google.runRequests.last().authorization)
    }

    @Test
    fun `a refused run call fails with the status code only, never the response body`() {
        google.runStatus = 403
        google.runBody = """{"error":{"message":"secret-detail-from-google"}}"""
        val e = assertFailsWith<VideoWakeException> { CloudRunWaker(props(), clock).wake() }
        assertTrue("403" in e.message!!)
        assertFalse("secret-detail" in e.message!!)
    }

    @Test
    fun `a refused token call fails and sends no run call`() {
        google.tokenStatus = 401
        assertFailsWith<VideoWakeException> { CloudRunWaker(props(), clock).wake() }
        assertEquals(0, google.runRequests.size)
    }

    @Test
    fun `an unreachable server is a wake failure, not a crash`() {
        val dead = props().copy(apiBase = "http://127.0.0.1:1")
        assertFailsWith<VideoWakeException> { CloudRunWaker(dead, clock).wake() }
    }

    @Test
    fun `a malformed credentials key fails at construction without echoing the key`() {
        val e = assertFailsWith<IllegalArgumentException> { CloudRunWaker(props(json = """{"client_email":"x@y","private_key":"not-a-key-SECRETVALUE"}"""), clock) }
        assertFalse("SECRETVALUE" in e.message!!)
    }

    @Test
    fun `names that could change the request path are refused`() {
        assertFailsWith<IllegalArgumentException> { CloudRunWaker(props().copy(job = "a/../b"), clock) }
    }
}
