package dev.sumin.skeleton.catalog

import dev.sumin.skeleton.catalogtest.CatalogTestBeans
import dev.sumin.skeleton.catalogtest.CatalogWebTestApplication
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentSubscriptions
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.ProviderPriceRefs
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/** 결제 쪽 조립(메모리 장부 + 가짜 결제사) - 구독을 켠 앱이 카탈로그 저장소만 붙이면 뜬다. */
@TestConfiguration(proxyBeanMethods = false)
class PaymentSide {
    @Bean fun inMemoryStore() = InMemoryPaymentLedgerStore()

    @Bean fun inMemoryTasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)

    @Bean fun scriptedGateway(store: InMemoryPaymentLedgerStore) = ScriptedGateway(now = java.time.Instant::now, openTransaction = store::hasOpenTransaction)

    @Bean fun textEncryptor(): dev.sumin.skeleton.crypto.TextEncryptor = ReversibleTestEncryptor
}

@TestConfiguration(proxyBeanMethods = false)
class AppOwnedContracts {
    @Bean fun appPlans(): SubscriptionPlans = object : SubscriptionPlans {
        override fun list() = listOf(SubscriptionPlan("app-plan", "App plan", PaymentAmount(1_000, "KRW"), "P1M", 1))
    }

    @Bean fun appCatalog(): PaymentCatalog = PaymentCatalog { throw UnsupportedOperationException() }
}

@SpringBootTest(classes = [CatalogWebTestApplication::class], properties = ["skeleton.payment.subscription.enabled=true"])
@Import(CatalogTestBeans::class, PaymentSide::class)
class CatalogAutoConfigurationTest {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `a subscription app with no plans of its own boots on the catalog, which fills the three payment contracts`() {
        assertTrue(context.getBean(SubscriptionPlans::class.java) is CatalogSubscriptionPlans)
        assertTrue(context.getBean(PaymentCatalog::class.java) is CatalogPaymentCatalog)
        assertTrue(context.getBean(ProviderPriceRefs::class.java) is CatalogProviderPriceRefs)
        assertEquals(emptyList(), context.getBean(PaymentSubscriptions::class.java).plans(), "the catalog is empty until an operator fills it")
    }
}

@SpringBootTest(classes = [CatalogWebTestApplication::class], properties = ["skeleton.payment.subscription.enabled=true"])
@Import(CatalogTestBeans::class, PaymentSide::class, AppOwnedContracts::class)
class CatalogAutoConfigurationAppWinsTest {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `a plans bean and a payment catalog bean of the app win over the catalog's`() {
        assertEquals(listOf("app-plan"), context.getBean(PaymentSubscriptions::class.java).plans().map { it.code })
        assertEquals(1, context.getBeansOfType(SubscriptionPlans::class.java).size)
        assertEquals(1, context.getBeansOfType(PaymentCatalog::class.java).size)
    }
}
