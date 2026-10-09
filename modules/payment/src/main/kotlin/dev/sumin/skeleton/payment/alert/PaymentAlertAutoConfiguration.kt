package dev.sumin.skeleton.payment.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.payment.PaymentLedgerAutoConfiguration
import dev.sumin.skeleton.payment.PaymentLedgerStore
import dev.sumin.skeleton.payment.PaymentProviderCredentialAlert
import dev.sumin.skeleton.payment.PaymentReviewNotifier
import dev.sumin.skeleton.payment.PaymentReviewReason
import java.time.Duration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** 주문 한 줄에 사람이 봐야 하는 표시가 찍혔다(`review_reason`). 키는 주문 id — 같은 주문은 같은 건으로 접힌다. */
enum class PaymentAlertKind(
    override val severity: AlertSeverity,
    override val title: String,
    override val minInterval: Duration = Duration.ZERO,
) : AlertKind {
    PAYMENT_REVIEW_NEEDED(AlertSeverity.ERROR, "A payment order needs a person to review it"),

    /** 결제사가 우리 키를 받아들이지 않았다 — 키 폐기 · 오타. 키는 결제사 id, 한 시간에 한 번으로 접는다(모든 결제가 거절되는 동안 결제마다 울리지 않게). */
    PAYMENT_PROVIDER_CREDENTIALS_REJECTED(AlertSeverity.ERROR, "The payment provider rejected our credentials", Duration.ofHours(1)),
}

/** 주인 경보로 내보낸다. **주문 id 말고는 아무 값도 싣지 않는다** — 금액 · 계정 · 검토 이유 문구가 경보 채널에 흘러가지 않게(이유는 줄에 있다). */
class OwnerAlertsPaymentReviewNotifier(private val alerts: OwnerAlerts) : PaymentReviewNotifier {
    override fun flagged(orderId: String, reason: PaymentReviewReason) {
        alerts.emit(PaymentAlertKind.PAYMENT_REVIEW_NEEDED, key = orderId)
    }
}

/** 결제사 id 만 싣는다 — 키 값 · 응답 문구는 경보 채널에 흘러가지 않는다. 트랜잭션 커밋과 무관하게 바로 낸다(일어난 사실이다). */
class OwnerAlertsPaymentProviderCredentialAlert(private val alerts: OwnerAlerts) : PaymentProviderCredentialAlert {
    override fun rejected(providerId: String) {
        alerts.emit(PaymentAlertKind.PAYMENT_PROVIDER_CREDENTIALS_REJECTED, key = providerId, immediate = true)
    }
}

/**
 * `alert` 모듈이 클래스패스에 있고 [OwnerAlerts] 빈이 있을 때만 — [PaymentLedgerAutoConfiguration] 보다 먼저 등록해 그쪽의 기본(경보 없음)을 물린다.
 * 장부([PaymentLedgerStore])가 없으면 이것도 빠진다. `alert` 가 없으면 이 클래스는 읽히지도 않는다(`src/noOptionalTest` 가 증명).
 */
@AutoConfiguration(
    before = [PaymentLedgerAutoConfiguration::class],
    afterName = [
        "dev.sumin.skeleton.alert.AlertAutoConfiguration",
        "dev.sumin.skeleton.payment.jdbc.PaymentJdbcAutoConfiguration",
    ],
)
@ConditionalOnClass(OwnerAlerts::class)
@ConditionalOnBean(PaymentLedgerStore::class)
class PaymentAlertAutoConfiguration {
    @Bean
    @ConditionalOnBean(OwnerAlerts::class)
    @ConditionalOnMissingBean(PaymentReviewNotifier::class)
    fun ownerAlertsPaymentReviewNotifier(alerts: OwnerAlerts): PaymentReviewNotifier = OwnerAlertsPaymentReviewNotifier(alerts)

    @Bean
    @ConditionalOnBean(OwnerAlerts::class)
    @ConditionalOnMissingBean(PaymentProviderCredentialAlert::class)
    fun ownerAlertsPaymentProviderCredentialAlert(alerts: OwnerAlerts): PaymentProviderCredentialAlert = OwnerAlertsPaymentProviderCredentialAlert(alerts)
}
