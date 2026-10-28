package dev.sumin.skeleton.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

class InMemoryNotificationBrokerTest {
    @Test
    fun `publish delivers matching topic notifications and reports delivery count`() {
        val broker = InMemoryNotificationBroker()
        val orderEvents = mutableListOf<NotificationEvent>()
        val allEvents = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) { event -> orderEvents += event }
        broker.subscribe(topics = emptySet()) { event -> allEvents += event }

        val result = broker.publish(
            NotificationEvent(
                topic = "orders",
                type = "created",
                title = "Order created",
                message = "Order A was created",
                payload = mapOf("orderId" to "A"),
            ),
        )

        assertEquals(2, result.deliveredSubscribers)
        assertEquals(result.eventId, orderEvents.single().id)
        assertEquals("orders", orderEvents.single().topic)
        assertEquals("A", orderEvents.single().payload["orderId"])
        assertEquals(1, allEvents.size)
    }

    @Test
    fun `publish skips non matching topic subscribers`() {
        val broker = InMemoryNotificationBroker()
        val received = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) { event -> received += event }

        val result = broker.publish(NotificationEvent(topic = "runs", type = "progress"))

        assertEquals(0, result.deliveredSubscribers)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `closed subscription no longer receives events`() {
        val broker = InMemoryNotificationBroker()
        val received = mutableListOf<NotificationEvent>()
        val subscription = broker.subscribe(topics = setOf("orders")) { event -> received += event }

        subscription.close()
        val result = broker.publish(NotificationEvent(topic = "orders", type = "created"))

        assertEquals(0, result.deliveredSubscribers)
        assertEquals(emptyList(), received)
    }

    @Test
    fun `failing subscriber is closed and does not prevent other deliveries`() {
        val broker = InMemoryNotificationBroker()
        var attempts = 0
        val received = mutableListOf<NotificationEvent>()
        broker.subscribe(topics = setOf("orders")) {
            attempts += 1
            throw IllegalStateException("subscriber disconnected")
        }
        broker.subscribe(topics = setOf("orders")) { event -> received += event }

        val first = broker.publish(NotificationEvent(topic = "orders", type = "created"))
        val second = broker.publish(NotificationEvent(topic = "orders", type = "updated"))

        assertEquals(1, first.deliveredSubscribers)
        assertEquals(1, second.deliveredSubscribers)
        assertEquals(1, attempts)
        assertEquals(listOf("created", "updated"), received.map { it.type })
    }

    @Test
    fun `event requires non blank topic and type`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationEvent(topic = " ", type = "created")
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationEvent(topic = "orders", type = " ")
        }
    }

    private fun project(id: String) = dev.sumin.skeleton.common.project.Project(id, listOf("$id.test"), id, dev.sumin.skeleton.common.project.ProjectDatasource(), emptyMap())
    private fun <T> inProject(id: String, block: () -> T) = dev.sumin.skeleton.common.project.ProjectContext.runIn(project(id), block)

    @Test
    fun `an event reaches the subscribers of its own project only - the same account id in another project hears nothing`() {
        val broker = InMemoryNotificationBroker()
        val heardInA = mutableListOf<NotificationEvent>()
        val heardInB = mutableListOf<NotificationEvent>()
        inProject("a") { broker.subscribe(emptySet()) { heardInA += it } }
        inProject("b") { broker.subscribe(emptySet()) { heardInB += it } }

        val result = inProject("a") { broker.publish(NotificationEvent(topic = "t", type = "x", recipientIds = setOf("acc_user"))) }

        assertEquals(1, result.deliveredSubscribers)
        assertEquals(1, heardInA.size)
        assertEquals(0, heardInB.size)
    }

    @Test
    fun `a server-level listener registered outside any project still hears every project`() {
        val broker = InMemoryNotificationBroker()
        val serverWide = mutableListOf<NotificationEvent>()
        broker.subscribe(emptySet()) { serverWide += it }

        inProject("a") { broker.publish(NotificationEvent(topic = "t", type = "x")) }
        inProject("b") { broker.publish(NotificationEvent(topic = "t", type = "x")) }

        assertEquals(2, serverWide.size)
    }

    @Test
    fun `an event published with no project is not handed to a subscriber of a project`() {
        val broker = InMemoryNotificationBroker()
        val heardInA = mutableListOf<NotificationEvent>()
        inProject("a") { broker.subscribe(emptySet()) { heardInA += it } }

        broker.publish(NotificationEvent(topic = "t", type = "x"))

        assertEquals(0, heardInA.size)
    }

    @Test
    fun `inside a transaction subscribers are told only after commit and a rollback tells nobody`() {
        val inbox = InMemoryNotificationInboxRepository()
        val broker = InMemoryNotificationBroker(inbox)
        val heard = mutableListOf<NotificationEvent>()
        broker.subscribe(emptySet()) { heard += it }

        // 롤백
        TransactionSynchronizationManager.initSynchronization()
        try {
            val result = broker.publish(NotificationEvent(topic = "t", type = "x", recipientIds = setOf("acc_user")))
            assertEquals(0, result.deliveredSubscribers)   // 미뤄진 발행은 0
            assertEquals(0, heard.size)
            // 저장은 호출자 트랜잭션 안에서 바로 — 롤백이면 같이 사라지는 것은 저장소(DB)의 몫이다
            assertEquals(1, inbox.findByRecipient("acc_user").totalElements)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        assertEquals(0, heard.size)

        // 커밋
        TransactionSynchronizationManager.initSynchronization()
        try {
            broker.publish(NotificationEvent(topic = "t", type = "x", recipientIds = setOf("acc_user")))
            assertEquals(0, heard.size)
            val syncs = TransactionSynchronizationManager.getSynchronizations()
            syncs.forEach { it.afterCommit() }
            syncs.forEach { it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED) }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        assertEquals(1, heard.size)
    }

    @Test
    fun `outside a transaction delivery is immediate`() {
        val broker = InMemoryNotificationBroker()
        val heard = mutableListOf<NotificationEvent>()
        broker.subscribe(emptySet()) { heard += it }

        val result = broker.publish(NotificationEvent(topic = "t", type = "x"))

        assertEquals(1, result.deliveredSubscribers)
        assertEquals(1, heard.size)
    }

    @Test
    fun `a deferred delivery still respects the project the event was published in`() {
        val broker = InMemoryNotificationBroker()
        val heardInA = mutableListOf<NotificationEvent>()
        val heardInB = mutableListOf<NotificationEvent>()
        inProject("a") { broker.subscribe(emptySet()) { heardInA += it } }
        inProject("b") { broker.subscribe(emptySet()) { heardInB += it } }

        TransactionSynchronizationManager.initSynchronization()
        try {
            inProject("a") { broker.publish(NotificationEvent(topic = "t", type = "x")) }
            // afterCommit 이 프로젝트 문맥 밖에서 불려도 발행한 프로젝트로 전달된다
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        assertEquals(1, heardInA.size)
        assertEquals(0, heardInB.size)
    }

    private class NoopTransactionManager : org.springframework.transaction.support.AbstractPlatformTransactionManager() {
        override fun doGetTransaction(): Any = Any()
        override fun doBegin(transaction: Any, definition: org.springframework.transaction.TransactionDefinition) = Unit
        override fun doCommit(status: org.springframework.transaction.support.DefaultTransactionStatus) = Unit
        override fun doRollback(status: org.springframework.transaction.support.DefaultTransactionStatus) = Unit
    }

    @Test
    fun `publishing from inside another afterCommit callback still delivers once the transaction is committed`() {
        val broker = InMemoryNotificationBroker()
        val heard = mutableListOf<NotificationEvent>()
        broker.subscribe(emptySet()) { heard += it }
        val tx = org.springframework.transaction.support.TransactionTemplate(NoopTransactionManager())

        tx.executeWithoutResult {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    val result = broker.publish(NotificationEvent(topic = "t", type = "x"))
                    assertEquals(0, result.deliveredSubscribers)   // 아직 미뤄진 상태
                }
            })
        }

        assertEquals(1, heard.size)   // 같은 트랜잭션이 끝나기 전에 afterCompletion 에서 전달됐다
    }

    @Test
    fun `a rollback delivers nothing even through the real transaction template`() {
        val broker = InMemoryNotificationBroker()
        val heard = mutableListOf<NotificationEvent>()
        broker.subscribe(emptySet()) { heard += it }
        val tx = org.springframework.transaction.support.TransactionTemplate(NoopTransactionManager())

        runCatching { tx.executeWithoutResult { broker.publish(NotificationEvent(topic = "t", type = "x")); error("boom") } }

        assertEquals(0, heard.size)
    }
}
