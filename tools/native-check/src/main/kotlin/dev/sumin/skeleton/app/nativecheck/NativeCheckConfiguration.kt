package dev.sumin.skeleton.app.nativecheck

import dev.sumin.skeleton.account.mail.AccountMail
import dev.sumin.skeleton.account.mail.AccountMailer
import dev.sumin.skeleton.common.web.PublicEndpointContributor
import dev.sumin.skeleton.payment.PaymentAmount
import dev.sumin.skeleton.payment.PaymentCatalog
import dev.sumin.skeleton.payment.PaymentItemNotPurchasableException
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PurchaseQuote
import dev.sumin.skeleton.payment.PurchaseRequest
import dev.sumin.skeleton.payment.SubscriptionPlan
import dev.sumin.skeleton.payment.SubscriptionPlans
import dev.sumin.skeleton.payment.testing.ScriptedGateway
import java.util.concurrent.CopyOnWriteArrayList
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 시험용 조립의 부품 — 가짜 결제사 · 상품 하나 · 플랜 하나 · 받은 메일을 모아 두는 메일러. 전부 이 앱에서만 쓴다(배포 대상이 아니다).
 * `/api/v1/native-check` 아래 경로는 시험 스크립트가 쓰는 보조 경로이고 인증 없이 열려 있다 — 이 앱을 인터넷에 내놓지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class NativeCheckConfiguration {
    @Bean
    fun scriptedGateway(store: PaymentLedgerStore): ScriptedGateway =
        ScriptedGateway(now = { java.time.Instant.now() }, openTransaction = store::hasOpenTransaction)

    @Bean
    fun nativeCheckCatalog(): PaymentCatalog =
        object : PaymentCatalog {
            override fun quote(request: PurchaseRequest): PurchaseQuote {
                if (request.itemType != "check" || request.itemRef != "check-1000" || request.quantity != 1) throw PaymentItemNotPurchasableException()
                return PurchaseQuote(PaymentAmount(1_000, "KRW"), "native check")
            }
        }

    @Bean
    fun nativeCheckPlans(): SubscriptionPlans =
        object : SubscriptionPlans {
            private val plans = listOf(SubscriptionPlan("basic", "Basic", PaymentAmount(3_900, "KRW"), "P1M", level = 1))
            override fun list(): List<SubscriptionPlan> = plans
        }

    @Bean
    fun recordingMailer(box: MailBox): AccountMailer = AccountMailer { box.sent += it }

    @Bean
    fun mailBox(): MailBox = MailBox()

    @Bean
    fun nativeCheckPublicEndpoints(): PublicEndpointContributor =
        PublicEndpointContributor { registry ->
            registry.add("GET", "/api/v1/native-check/**")
            registry.add("POST", "/api/v1/native-check/**")
        }
}

class MailBox {
    val sent = CopyOnWriteArrayList<AccountMail>()
}
