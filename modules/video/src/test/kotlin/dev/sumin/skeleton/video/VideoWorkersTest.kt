package dev.sumin.skeleton.video

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.common.time.TimeProvider
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 일꾼 토큰 — 표에는 SHA-256 만, 평문은 발급 응답에만 한 번. 폐기하면 바로 못 쓴다. 로컬 전용 해시 설정은 stage · prod 에서 기동 거부 */
class VideoWorkersTest {
    private val now = Instant.parse("2026-11-04T00:00:00Z")
    private val store = FakeVideoWorkerStore()
    private val service = VideoWorkerService(store, TimeProvider { now })
    private fun sha(s: String) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    @Test
    fun `issue returns the plain token once and stores only its hash`() {
        val issued = service.issue("home", setOf(VideoLane.REMUX, VideoLane.COMPAT))

        assertEquals(setOf(VideoLane.REMUX, VideoLane.COMPAT), issued.worker.lanes)
        assertTrue(issued.token.length >= 43)
        assertTrue(store.existsByTokenSha256(sha(issued.token)))
        assertFalse(store.existsByTokenSha256(issued.token), "the plain token is nowhere in the table")
    }

    @Test
    fun `a token authenticates until it is revoked, and an unknown or blank one never does`() {
        val issued = service.issue("home", VideoLane.entries.toSet())

        assertEquals(issued.worker.id, service.authenticate(issued.token)?.id)
        assertEquals(now, store.rows.getValue(issued.worker.id).lastSeenAt)
        assertNull(service.authenticate(issued.token + "x"))
        assertNull(service.authenticate(""))
        assertNull(service.authenticate("x".repeat(500)))

        assertTrue(service.revoke(issued.worker.id))
        assertNull(service.authenticate(issued.token))
        assertFalse(service.revoke(issued.worker.id), "revoking twice changes nothing")
    }

    @Test
    fun `issue refuses a bad name or no lane`() {
        assertFailsWith<VideoWorkerInvalidException> { service.issue("bad name!", setOf(VideoLane.REMUX)) }
        assertFailsWith<VideoWorkerInvalidException> { service.issue("home", emptySet()) }
    }

    @Test
    fun `the configured local hash registers one worker with every lane, once, and a revoked one stays revoked`() {
        val hash = sha("local-dev-token")
        val props = VideoProperties(enabled = true, workerTokenSha256 = hash)
        VideoWorkerBootstrap(props, store, TimeProvider { now }).register()
        VideoWorkerBootstrap(props, store, TimeProvider { now }).register()

        assertEquals(1, store.rows.size)
        val worker = assertNotNull(service.authenticate("local-dev-token"))
        assertEquals(VideoLane.entries.toSet(), worker.lanes)

        service.revoke(worker.id)
        VideoWorkerBootstrap(props, store, TimeProvider { now }).register()
        assertEquals(1, store.rows.size)
        assertNull(service.authenticate("local-dev-token"))
    }

    @Test
    fun `a hash that is not 64 hex characters stops the start without echoing the value`() {
        val e = assertFailsWith<IllegalArgumentException> { VideoProperties(workerTokenSha256 = "plain-token-not-a-hash") }
        assertFalse("plain-token-not-a-hash" in e.message!!)
    }

    @Test
    fun `the deploy guard refuses the local worker hash in stage and prod, and says nothing elsewhere`() {
        val hash = sha("local-dev-token")
        val guard = VideoDeployGuard(VideoProperties(enabled = true, workerTokenSha256 = hash))
        fun ctx(env: DeployEnv?) = DeployContext(env, emptySet())

        listOf(DeployEnv.STAGE, DeployEnv.PROD).forEach { env ->
            val problems = guard.problems(ctx(env))
            assertEquals(1, problems.size, env.toString())
            assertTrue("worker-token-sha256" in problems.single() && hash !in problems.single())
        }
        assertTrue(guard.problems(ctx(DeployEnv.LOCAL)).isEmpty() && guard.problems(ctx(null)).isEmpty())
        assertTrue(VideoDeployGuard(VideoProperties(enabled = true)).problems(ctx(DeployEnv.PROD)).isEmpty())
    }
}
