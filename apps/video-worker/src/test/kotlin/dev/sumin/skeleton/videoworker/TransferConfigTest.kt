package dev.sumin.skeleton.videoworker

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class TransferConfigTest {
    @Test
    fun `서명 주소 출처를 바꿔도 경로와 질의는 그대로 · 원래 Host 를 알려 준다`() {
        val t = Transfer("http://s3:8333")
        val r = t.resolve("http://s3.test.localhost:3902/bucket/uploads/y?X-Amz-Signature=abc&X-Amz-Expires=3600")
        assertEquals("http://s3:8333/bucket/uploads/y?X-Amz-Signature=abc&X-Amz-Expires=3600", r.url)
        assertEquals("s3.test.localhost:3902", r.host)
        assertEquals("Host: s3.test.localhost:3902\r\n", r.ffmpegHeaders)
        val plain = Transfer(null).resolve("https://x.r2.example/a?b=1")
        assertEquals("https://x.r2.example/a?b=1", plain.url)
        assertNull(plain.host)
        assertNull(plain.ffmpegHeaders)
    }

    @Test
    fun `루트 기준 입력 경로는 API 출처로 풀고 · 절대 주소는 그대로 · 저장소 출처 치환을 타지 않는다`() {
        val t = Transfer("http://s3:8333", "http://server:8080")
        val rel = t.resolve("/api/v1/videos/play/tok/o-abc/index.m3u8")
        assertEquals("http://server:8080/api/v1/videos/play/tok/o-abc/index.m3u8", rel.url)
        assertNull(rel.host)
        assertEquals("http://server:8080/api/v1/videos/play/tok/o-abc/index.m3u8?x=1", Transfer(null, "http://server:8080").resolve("/api/v1/videos/play/tok/o-abc/index.m3u8?x=1").url)
        assertEquals("https://x.example/a.m3u8", Transfer(null, "http://server:8080").resolve("https://x.example/a.m3u8").url)
    }

    @Test
    fun `올릴 때 서명이 준 헤더는 그대로 · host 와 content-length 는 우리가 정하지 않고 원래 Host 를 보낸다`() {
        FakeVideoServer().use { server ->
            val file = Files.createTempFile("up", ".bin").also { Files.write(it, ByteArray(10) { 7 }) }
            try {
                val target = UploadUrl("o.mp4", "PUT", "http://signed.example.test:9999/store/j/o.mp4", mapOf("cache-control" to "public, max-age=31536000, immutable", "host" to "signed.example.test:9999", "content-length" to "99"))
                Transfer(server.base).upload(target, file)
                val path = "/store/j/o.mp4"
                assertEquals(10, server.stored[path]!!.size)
                assertEquals("public, max-age=31536000, immutable", server.storedHeaders[path]!!["cache-control"])
                assertEquals("signed.example.test:9999", server.storedHeaders[path]!!["host"], "서명에 든 Host 를 그대로")
                assertEquals("10", server.storedHeaders[path]!!["content-length"])
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `내려받은 크기가 다르면 실패`() {
        FakeVideoServer().use { server ->
            val src = Files.createTempFile("src", ".bin").also { Files.write(it, ByteArray(20)) }
            val dest = Files.createTempFile("dest", ".bin")
            try {
                val url = server.serve("a.bin", src)
                Transfer(null).download(url, dest, 20)
                assertFailsWith<TransferException> { Transfer(null).download(url, dest, 21) }
            } finally {
                Files.deleteIfExists(src); Files.deleteIfExists(dest)
            }
        }
    }

    @Test
    fun `환경변수 설정 — 필수 · 차선 · 모양 · 토큰은 문자열에 안 나온다`() {
        val env = mapOf("VIDEO_WORKER_API" to "http://server:8080/", "VIDEO_WORKER_TOKEN" to "secret-token-value", "VIDEO_WORKER_LANES" to "remux, compat", "VIDEO_WORKER_MODE" to "once")
        val c = WorkerConfig.fromEnv(env)
        assertEquals("http://server:8080", c.apiBase)
        assertEquals(setOf("REMUX", "COMPAT"), c.lanes)
        assertEquals(Mode.ONCE, c.mode)
        assertFalse(c.toString().contains("secret-token-value"))
        assertFailsWith<IllegalStateException> { WorkerConfig.fromEnv(env - "VIDEO_WORKER_TOKEN") }
        assertFailsWith<IllegalArgumentException> { WorkerConfig.fromEnv(env + ("VIDEO_WORKER_LANES" to "REMUX,WARP")) }
        assertEquals(setOf("REMUX", "COMPAT", "LADDER", "ANIMATED"), WorkerConfig.fromEnv(env - "VIDEO_WORKER_LANES").lanes)
    }

    @Test
    fun `환경변수 기본값과 덮어쓰기 — 쉬는 간격 5초 · ffmpeg 180분 · nice 0 · 일꾼 이름 · 헬스 포트`() {
        val env = mapOf("VIDEO_WORKER_API" to "http://server:8080", "VIDEO_WORKER_TOKEN" to "secret-token-value")
        val d = WorkerConfig.fromEnv(env)
        assertEquals(java.time.Duration.ofSeconds(5), d.pollInterval)
        assertEquals(java.time.Duration.ofMinutes(180), d.ffmpegTimeout)
        assertEquals(0, d.nice)
        assertEquals(Mode.POLL, d.mode)
        assertEquals(8080, d.healthPort)
        assertEquals(true, d.workerId.startsWith("worker-"))
        val c = WorkerConfig.fromEnv(env + mapOf("VIDEO_WORKER_POLL_SECONDS" to "15", "VIDEO_WORKER_FFMPEG_TIMEOUT_MINUTES" to "60", "VIDEO_WORKER_NICE" to "10", "VIDEO_WORKER_ID" to "home-1", "VIDEO_WORKER_HEALTH_PORT" to "9090"))
        assertEquals(java.time.Duration.ofSeconds(15), c.pollInterval)
        assertEquals(java.time.Duration.ofMinutes(60), c.ffmpegTimeout)
        assertEquals(10, c.nice)
        assertEquals("home-1", c.workerId)
        assertEquals(9090, c.healthPort)
    }

    @Test
    fun `일 시간 한도는 기본 4시간 — 환경변수로 바꾼다`() {
        val env = mapOf("VIDEO_WORKER_API" to "http://server:8080", "VIDEO_WORKER_TOKEN" to "secret-token-value")
        assertEquals(java.time.Duration.ofHours(4), WorkerConfig.fromEnv(env).jobTimeout)
        assertEquals(java.time.Duration.ofMinutes(30), WorkerConfig.fromEnv(env + ("VIDEO_WORKER_JOB_TIMEOUT_MINUTES" to "30")).jobTimeout)
    }
}

class ApiLeaseLostTest {
    @Test
    fun `서버가 영상을 모른다(404 — 지웠거나 내렸다)고 하면 임대를 잃은 것으로 본다`() {
        FakeVideoServer().use { server ->
            val api = WorkerApi(WorkerConfig(apiBase = server.base + "/nope", token = server.token, workerId = "t-1"))
            assertFailsWith<LeaseLostException> { api.heartbeat("gone-job") }
        }
    }
}
