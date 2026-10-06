package dev.sumin.skeleton.account.abuse

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountTaskRunnerTest {
    @Test
    fun `closing waits for the work already queued - a deploy does not throw away pending mail`() {
        val runner = ExecutorAccountTaskRunner(threads = 1, queue = 10)
        val done = AtomicBoolean(false)
        val started = CountDownLatch(1)
        runner.run("slow") { started.countDown(); Thread.sleep(300); done.set(true) }   // 300ms 는 "닫을 때 아직 안 끝났다"를 위한 것 — 늦어져도 close 가 기다리므로 부하와 무관
        assertTrue(started.await(30, TimeUnit.SECONDS))
        runner.close()
        assertTrue(done.get(), "shutdown() alone returns at once and the daemon thread dies with the JVM")
    }

    @Test
    fun `a full queue pushes back on the caller instead of dropping the task`() {
        val runner = ExecutorAccountTaskRunner(threads = 1, queue = 1)
        val gate = CountDownLatch(1)
        runner.run("blocker") { gate.await(5, TimeUnit.SECONDS) }      // occupies the only thread
        runner.run("queued") { }                                       // fills the queue
        val ranOn = AtomicReference<Thread>()
        runner.run("overflow") { ranOn.set(Thread.currentThread()) }   // nowhere to wait: must not vanish
        assertEquals(Thread.currentThread(), ranOn.get(), "an overflowing task is run by the caller, never silently dropped")
        assertNotEquals(null, ranOn.get())
        gate.countDown()
        runner.close()
    }
}
