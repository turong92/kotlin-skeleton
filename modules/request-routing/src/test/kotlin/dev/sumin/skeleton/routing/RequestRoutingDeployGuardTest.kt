package dev.sumin.skeleton.routing

import dev.sumin.skeleton.common.deploy.DeployContext
import dev.sumin.skeleton.common.deploy.DeployEnv
import dev.sumin.skeleton.jobqueue.jdbc.JobLanes
import dev.sumin.skeleton.jobqueue.jdbc.JobQueueWorker
import dev.sumin.skeleton.routing.fake.FakeFlyway
import dev.sumin.skeleton.routing.fake.FakeScheduler
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.mock.env.MockEnvironment

class RequestRoutingDeployGuardTest {
    private class Fixture(
        val properties: RequestRoutingProperties,
        val env: MockEnvironment = MockEnvironment(),
        val beans: DefaultListableBeanFactory = DefaultListableBeanFactory(),
    ) {
        fun bean(type: Class<*>, name: String = type.simpleName.replaceFirstChar { it.lowercase() }) {
            beans.registerBeanDefinition(name, RootBeanDefinition(type))
        }

        fun lanes(workerLane: String?) {
            beans.registerSingleton(
                "lanes",
                object : JobLanes {
                    override fun enqueueLane(): String? = null
                    override fun workerLane(): String? = workerLane
                },
            )
        }

        fun store() {
            beans.registerSingleton(
                "store",
                object : LocalRouteWindowStore {
                    override fun findOpen(now: Instant): LocalWindow? = null
                    override fun open(openedBy: String, reason: String, now: Instant, openUntil: Instant) = error("not used")
                    override fun closeAll(closedBy: String, now: Instant): List<Long> = emptyList()
                },
            )
        }

        fun guard() = RequestRoutingDeployGuard(properties, RouteTargets(properties), beans, env, FakeScheduler::class.java.name, FakeFlyway::class.java.name)
        fun problems(context: DeployContext = DeployContext(null, emptySet())) = guard().problems(context)
        fun warnings(context: DeployContext = DeployContext(null, emptySet())) = guard().warnings(context)
    }

    private fun candidate(service: String = "orders", instance: String = "candidate", services: Map<String, RequestRoutingProperties.Service> = emptyMap()) =
        Fixture(RequestRoutingProperties(enabled = true, service = service, instance = instance, services = services))

    private fun Fixture.assertOneProblemMentioning(property: String) {
        val found = problems()
        assertEquals(1, found.size, found.toString())
        assertTrue(property in found.single(), found.single())
    }

    @Test
    fun `candidate with a full safe setup has no problems`() {
        assertEquals(emptyList(), candidate().problems())
    }

    @Test
    fun `candidateWithoutServiceFails`() = candidate(service = "").assertOneProblemMentioning("skeleton.routing.service")

    @Test
    fun `candidateWithLocalSchedulerFails`() {
        val f = candidate().also { it.bean(FakeScheduler::class.java) }
        f.assertOneProblemMentioning("skeleton.scheduler.mode")
        f.env.setProperty("skeleton.scheduler.mode", "external")
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `candidateLaneWorkerBoots`() {
        val f = candidate().also { it.bean(JobQueueWorker::class.java); it.lanes("candidate") }
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `candidateWorkerWithoutLaneFails`() {
        val f = candidate().also { it.bean(JobQueueWorker::class.java); it.lanes(null) }
        f.assertOneProblemMentioning("skeleton.job-queue.mode")
        f.env.setProperty("skeleton.job-queue.mode", "on-request")
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `candidate worker with no JobLanes bean at all fails`() {
        candidate().also { it.bean(JobQueueWorker::class.java) }.assertOneProblemMentioning("skeleton.job-queue.mode")
    }

    @Test
    fun `candidateKafkaWithCustomizerBoots`() {
        val f = candidate().also { it.bean(KafkaListenerEndpointRegistry::class.java); it.beans.registerSingleton("routeKafkaContainerCustomizer", Any()) }
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `candidateKafkaWithoutCustomizerFails`() {
        val f = candidate().also { it.bean(KafkaListenerEndpointRegistry::class.java) }
        f.assertOneProblemMentioning("spring.kafka.listener.auto-startup")
        f.env.setProperty("spring.kafka.listener.auto-startup", "false")
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `candidateWithFlywayFails`() {
        candidate().also { it.bean(FakeFlyway::class.java) }.assertOneProblemMentioning("spring.flyway.enabled")
    }

    @Test
    fun `baselineIgnoresInstanceRules`() {
        val f = candidate(service = "", instance = "").also {
            it.bean(FakeScheduler::class.java)
            it.bean(JobQueueWorker::class.java)
            it.bean(KafkaListenerEndpointRegistry::class.java)
            it.bean(FakeFlyway::class.java)
        }
        assertEquals(emptyList(), f.problems())
    }

    @Test
    fun `maxWindowOver8hFails`() {
        val f = Fixture(RequestRoutingProperties(enabled = true, local = RequestRoutingProperties.Local(maxWindow = Duration.ofHours(9))))
        f.assertOneProblemMentioning("skeleton.routing.local.max-window")
    }

    @Test
    fun `adminWithoutStoreFails`() {
        val f = Fixture(RequestRoutingProperties(enabled = true, admin = RequestRoutingProperties.Admin(enabled = true)))
        f.assertOneProblemMentioning("skeleton.routing.admin.enabled")
        f.store()
        assertEquals(emptyList(), f.problems())
    }

    private val localService = mapOf(
        "orders" to RequestRoutingProperties.Service(mapOf("alice" to RequestRoutingProperties.Target("https://alice.example.com", local = true))),
    )

    @Test
    fun `protectedLocalTargetsWithoutStoreWarns`() {
        val f = Fixture(RequestRoutingProperties(enabled = true, services = localService))
        val prod = DeployContext(DeployEnv.PROD, emptySet())
        assertEquals(1, f.warnings(prod).size)
        assertTrue("skeleton.routing.services" in f.warnings(prod).single())
        assertEquals(emptyList(), f.warnings(DeployContext(DeployEnv.LOCAL, emptySet())))
        f.store()
        assertEquals(emptyList(), f.warnings(prod))
    }

    @Test
    fun `jobQueueWithoutRouteKeyWarns`() {
        val f = candidate(instance = "").also { it.bean(JobQueueWorker::class.java) }
        assertTrue(f.warnings().single().contains("skeleton.job-queue.propagated-mdc-keys"))
        f.env.setProperty("skeleton.job-queue.propagated-mdc-keys", "traceId,route")
        assertEquals(emptyList(), f.warnings())
    }

    @Test
    fun `messagesCarryNamesNotValues`() {
        val secretUrl = "https://secret-host-9317.example.com"
        val f = Fixture(
            RequestRoutingProperties(
                enabled = true,
                service = "",
                instance = "secret-instance",
                admin = RequestRoutingProperties.Admin(enabled = true),
                local = RequestRoutingProperties.Local(maxWindow = Duration.ofMinutes(777)),
                services = mapOf("orders" to RequestRoutingProperties.Service(mapOf("alice" to RequestRoutingProperties.Target(secretUrl, local = true)))),
            ),
        ).also { it.bean(JobQueueWorker::class.java); it.bean(FakeFlyway::class.java) }
        val all = f.problems(DeployContext(DeployEnv.PROD, emptySet())) + f.warnings(DeployContext(DeployEnv.PROD, emptySet()))
        assertTrue(all.isNotEmpty())
        all.forEach { message ->
            assertTrue("secret-host-9317" !in message && "secret-instance" !in message && "777" !in message && "PT" !in message, message)
        }
    }
}
