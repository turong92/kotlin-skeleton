package dev.sumin.skeleton.iaptest

import dev.sumin.skeleton.crypto.TextEncryptor
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentOrderListener
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.FakeStoreGateway
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.RecordingOrderListener
import dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
import dev.sumin.skeleton.payment.testing.TestSubscriptionPlans
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 - 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class IapWebTestApplication

/** 메모리 장부 + 가짜 스토어 둘(`appstore` · `googleplay`) + 단건 카탈로그 + 구독 플랜 - 진짜 자동설정 순서로 컨트롤러까지 세운다. */
@TestConfiguration(proxyBeanMethods = false)
class IapLedgerBeans {
    @Bean fun inMemoryStore() = InMemoryPaymentLedgerStore()

    @Bean fun inMemoryTasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)

    @Bean fun appstore() = FakeStoreGateway("appstore")

    @Bean fun googleplay() = FakeStoreGateway("googleplay")

    @Bean fun catalog(): PaymentCatalog = CheerCatalog()

    @Bean fun listener(): PaymentOrderListener = RecordingOrderListener()

    @Bean fun subscriptionPlans(): SubscriptionPlans = TestSubscriptionPlans()

    @Bean fun textEncryptor(): TextEncryptor = ReversibleTestEncryptor
}
