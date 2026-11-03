package dev.sumin.skeleton.videoworker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 서버 일꾼 API 계약 한 장(`/api/internal/video/…`)과 모양이 맞는지 — 가짜 서버에 서버와 같은 봉투 · 필드로 말하게 한다 */
class ApiContractTest {
    private fun api(server: FakeVideoServer) = WorkerApi(WorkerConfig(apiBase = server.base, token = server.token, workerId = "w-1"))

    @Test
    fun `claim — value 봉투를 풀고 verifyDecode · origFolder · build 를 읽는다, 일이 없으면 null`() {
        FakeVideoServer().use { server ->
            server.enqueue(server.job("j1", "REMUX", "http://x/in", 10, mapOf("verifyDecode" to true, "origFolder" to "o-0123456789abcdef", "build" to "b")))
            val job = assertNotNull(api(server).claim(setOf("REMUX")))
            assertEquals("j1", job.jobId)
            assertEquals("REMUX", job.lane)
            assertTrue(job.verifyDecode)
            assertEquals("o-0123456789abcdef", job.origFolder)
            assertNull(api(server).claim(setOf("REMUX")), "204")
        }
    }

    @Test
    fun `heartbeat 는 진행률을 0에서 100 으로 실어 보낸다 · 모르면 싣지 않는다`() {
        FakeVideoServer().use { server ->
            val api = api(server)
            api.heartbeat("j1")
            api.heartbeat("j1", 42)
            api.heartbeat("j1", 250)
            assertEquals(listOf(42, 100), server.progresses["j1"]?.toList())
            assertEquals(3, server.heartbeats)
        }
    }

    @Test
    fun `upload-urls 는 names 목록을 보내고 values 의 서명 주소 · 헤더를 읽는다`() {
        FakeVideoServer().use { server ->
            val urls = api(server).uploadUrls("j1", listOf("o-0123456789abcdef/index.m3u8", "p-0123456789abcdef.jpg"))
            assertEquals(listOf("o-0123456789abcdef/index.m3u8", "p-0123456789abcdef.jpg"), urls.map { it.name })
            assertEquals("PUT", urls.first().method)
            assertEquals("public, max-age=31536000, immutable", urls.first().headers["cache-control"])
        }
    }

    @Test
    fun `complete 는 없는 칸을 null 로 싣지 않는다 — 차선은 어느 칸이 있느냐로 정해진다`() {
        FakeVideoServer().use { server ->
            api(server).complete("j1", CompleteBody("w-1", compat = ResultFile("c-0123456789abcdef/index.m3u8", 5, "ab")))
            val body = tools.jackson.databind.json.JsonMapper.builder().build().readTree(server.completes["j1"])
            assertEquals(setOf("workerId", "compat"), body.propertyNames().toSet())
            assertEquals(setOf("name", "bytes", "sha256"), body["compat"].propertyNames().toSet())
        }
    }
}
