package dev.sumin.skeleton.paymenttest

import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentTasks
import dev.sumin.skeleton.payment.testing.CheerCatalog
import dev.sumin.skeleton.payment.testing.InMemoryPaymentLedgerStore
import dev.sumin.skeleton.payment.testing.InMemoryPaymentTasks
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class PaymentWebTestApplication

/** 메모리 장부 + 서명 없는 가짜 결제사 한 벌 — 웹 시험이 진짜 자동설정 순서로 컨트롤러까지 세운다. */
@TestConfiguration(proxyBeanMethods = false)
class InMemoryLedgerBeans {
    @Bean fun inMemoryStore() = InMemoryPaymentLedgerStore()

    @Bean fun inMemoryTasks(store: InMemoryPaymentLedgerStore): PaymentTasks = InMemoryPaymentTasks(store)

    @Bean fun scriptedGateway(store: InMemoryPaymentLedgerStore) = ScriptedGateway(now = java.time.Instant::now, openTransaction = store::hasOpenTransaction) // 앱의 시계(실제 시각)와 같이 - 고정 날짜면 7일 철회 같은 날짜 규칙이 날이 가면 깨진다

    @Bean fun catalog(): PaymentCatalog = CheerCatalog()
}

/** 구독을 켠 시험 조립 — 플랜 · 암호기 대역. `skeleton.payment.subscription.enabled=true` 와 함께 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
class SubscriptionBeans {
    @Bean fun subscriptionPlans(): dev.sumin.skeleton.payment.SubscriptionPlans = dev.sumin.skeleton.payment.testing.TestSubscriptionPlans()

    @Bean fun textEncryptor(): dev.sumin.skeleton.crypto.TextEncryptor = dev.sumin.skeleton.payment.testing.ReversibleTestEncryptor
}
