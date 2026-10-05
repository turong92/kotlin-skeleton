package dev.sumin.skeleton.board.jdbc

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** [threads] 개 스레드가 같은 순간에 출발해 [block] 을 돈다 (인덱스를 받는다). 예외는 get() 에서 그대로 터진다. */
fun <T> parallel(threads: Int, block: (Int) -> T): List<T> {
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    try {
        val futures = (0 until threads).map { i -> pool.submit<T> { start.await(); block(i) } }
        start.countDown()
        return futures.map { it.get() }
    } finally {
        pool.shutdownNow()
    }
}
