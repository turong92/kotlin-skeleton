package dev.sumin.skeleton.redis.lock

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat
import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.ConstantDelay
import org.redisson.config.Config
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class RedisLockConcurrencyIntegrationTest {
    private lateinit var redissonClient: RedissonClient
    private lateinit var executor: DistributedLockExecutor

    @BeforeTest
    fun setUp() {
        val config = Config().apply {
            useSingleServer()
                .setAddress("redis://${redis.host}:${redis.getMappedPort(6379)}")
                .setTimeout(1_000)
                .setRetryAttempts(1)
                .setRetryDelay(ConstantDelay(Duration.ofMillis(100)))
        }
        redissonClient = Redisson.create(config)
        executor = DistributedLockExecutor(RedissonDistributedLockBackend(redissonClient))
    }

    @AfterTest
    fun tearDown() {
        redissonClient.shutdown()
    }

    @Test
    fun `same key runs only one action under concurrent attempts`() {
        val pool = Executors.newFixedThreadPool(2)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val enteredActions = AtomicInteger(0)

        val first = pool.submit<String?> {
            executor.execute(
                key = "test:lock:order:1",
                request = request(waitTimeMillis = 1_000, leaseTimeMillis = 3_000),
            ) {
                enteredActions.incrementAndGet()
                firstStarted.countDown()
                assertThat(releaseFirst.await(3, TimeUnit.SECONDS)).isTrue()
                "first"
            }
        }
        assertThat(firstStarted.await(3, TimeUnit.SECONDS)).isTrue()

        val second = pool.submit<String?> {
            executor.execute(
                key = "test:lock:order:1",
                request = request(
                    waitTimeMillis = 100,
                    leaseTimeMillis = 3_000,
                    failurePolicy = LockFailurePolicy.SKIP,
                ),
            ) {
                enteredActions.incrementAndGet()
                "second"
            }
        }

        assertThat(second.get(3, TimeUnit.SECONDS)).isNull()
        releaseFirst.countDown()
        assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("first")
        assertThat(enteredActions.get()).isEqualTo(1)
        pool.shutdownNow()
    }

    @Test
    fun `different keys run concurrently`() {
        val pool = Executors.newFixedThreadPool(2)
        val activeActions = AtomicInteger(0)
        val bothInside = CountDownLatch(2)
        val releaseBoth = CountDownLatch(1)

        fun submitLocked(key: String) =
            pool.submit<String?> {
                executor.execute(
                    key = key,
                    request = request(waitTimeMillis = 1_000, leaseTimeMillis = 3_000),
                ) {
                    if (activeActions.incrementAndGet() == 2) {
                        bothInside.countDown()
                    }
                    bothInside.countDown()
                    assertThat(releaseBoth.await(3, TimeUnit.SECONDS)).isTrue()
                    activeActions.decrementAndGet()
                    key
                }
            }

        val first = submitLocked("test:lock:order:1")
        val second = submitLocked("test:lock:order:2")

        assertThat(bothInside.await(3, TimeUnit.SECONDS)).isTrue()
        releaseBoth.countDown()
        assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("test:lock:order:1")
        assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo("test:lock:order:2")
        pool.shutdownNow()
    }

    private fun request(
        waitTimeMillis: Long,
        leaseTimeMillis: Long,
        failurePolicy: LockFailurePolicy = LockFailurePolicy.THROW,
    ): DistributedLockRequest =
        DistributedLockRequest(
            waitTime = waitTimeMillis,
            leaseTime = leaseTimeMillis,
            timeUnit = TimeUnit.MILLISECONDS,
            retryAttempts = 0,
            retryBackoff = Duration.ZERO,
            failurePolicy = failurePolicy,
        )

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> =
            GenericContainer(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
    }
}
