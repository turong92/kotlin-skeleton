package dev.sumin.skeleton.persistence.jpa

import dev.sumin.skeleton.common.time.TimeProvider
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Persistence
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JpaOperationalPatternsTest {
    private lateinit var entityManagerFactory: EntityManagerFactory
    private lateinit var entityManager: EntityManager

    @BeforeTest
    fun setUp() {
        entityManagerFactory = Persistence.createEntityManagerFactory("persistence-jpa-test")
        entityManager = entityManagerFactory.createEntityManager()
    }

    @AfterTest
    fun tearDown() {
        entityManager.close()
        entityManagerFactory.close()
    }

    @Test
    fun `versioned jpa entity provides opt-in optimistic locking column`() {
        val version = VersionedJpaEntity::class.java.getDeclaredField("version")

        assertNotNull(version.getAnnotation(Version::class.java))
        assertEquals("version", version.getAnnotation(Column::class.java).name)
    }

    @Test
    fun `partial update changes selected columns and guards expected version`() {
        val orderId = persistOrder(customerName = "Alice", status = "READY")
        val original = entityManager.find(SampleOrderEntity::class.java, orderId)
        val originalCreatedAt = original.audit.createdAt
        val originalVersion = original.version ?: error("version should be assigned")
        entityManager.clear()

        val updatedRows = transaction {
            JpaPartialUpdateExecutor(entityManager).updateById(
                entityClass = SampleOrderEntity::class.java,
                id = orderId,
                assignments = mapOf("status" to "PAID"),
                options = JpaPartialUpdateOptions(
                    expectedVersion = originalVersion,
                    incrementVersion = true,
                    touchUpdatedAtPath = "audit.updatedAt",
                    timeProvider = TimeProvider.fixed(Instant.parse("2026-06-13T01:02:03.123456789Z")),
                ),
            )
        }
        entityManager.clear()

        val updated = entityManager.find(SampleOrderEntity::class.java, orderId)
        assertEquals(1, updatedRows)
        assertEquals("Alice", updated.customerName)
        assertEquals("PAID", updated.status)
        assertEquals(originalVersion + 1, updated.version)
        assertEquals(originalCreatedAt, updated.audit.createdAt)
        assertEquals(Instant.parse("2026-06-13T01:02:03.123456Z"), updated.audit.updatedAt)

        val staleRows = transaction {
            JpaPartialUpdateExecutor(entityManager).updateById(
                entityClass = SampleOrderEntity::class.java,
                id = orderId,
                assignments = mapOf("status" to "CANCELLED"),
                options = JpaPartialUpdateOptions(expectedVersion = originalVersion),
            )
        }

        assertEquals(0, staleRows)
    }

    @Test
    fun `fetch graph helper creates standard hints for association loading`() {
        val hints = JpaFetchGraphHints(entityManager).fetchGraphHints(
            SampleCustomerEntity::class.java,
            "orders",
            "orders.lines",
        )

        assertTrue(JpaFetchGraphHints.FETCH_GRAPH_HINT in hints)
        assertNotNull(hints[JpaFetchGraphHints.FETCH_GRAPH_HINT])
    }

    private fun persistOrder(
        customerName: String,
        status: String,
    ): Long =
        transaction {
            SampleOrderEntity().also { order ->
                order.customerName = customerName
                order.status = status
                entityManager.persist(order)
            }.id ?: error("id should be assigned")
        }

    private fun <T> transaction(block: () -> T): T {
        entityManager.transaction.begin()
        return try {
            block().also {
                entityManager.transaction.commit()
            }
        } catch (error: Throwable) {
            if (entityManager.transaction.isActive) {
                entityManager.transaction.rollback()
            }
            throw error
        }
    }
}

@Entity
@Table(name = "sample_orders")
open class SampleOrderEntity : VersionedJpaEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @Column(name = "customer_name", nullable = false)
    open var customerName: String = ""

    @Column(name = "status", nullable = false)
    open var status: String = ""
}

@Entity
@Table(name = "sample_customers")
open class SampleCustomerEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @OneToMany(mappedBy = "customer", fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    open var orders: MutableList<SampleOrderLineEntity> = mutableListOf()
}

@Entity
@Table(name = "sample_order_lines")
open class SampleOrderLineEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    @ManyToOne(fetch = FetchType.LAZY)
    open var customer: SampleCustomerEntity? = null

    @OneToMany(fetch = FetchType.LAZY, cascade = [CascadeType.ALL])
    open var lines: MutableList<SampleOrderEntity> = mutableListOf()
}
