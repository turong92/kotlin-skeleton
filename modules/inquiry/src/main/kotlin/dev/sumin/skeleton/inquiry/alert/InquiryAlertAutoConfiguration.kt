package dev.sumin.skeleton.inquiry.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.inquiry.InquiryAutoConfiguration
import dev.sumin.skeleton.inquiry.InquiryListener
import dev.sumin.skeleton.inquiry.InquiryProperties
import java.time.Duration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** inquiry 가 스스로 내는 경보 종류 — 고정 키 하나로 [minInterval](`skeleton.inquiry.alert-interval`) 안에는 접는다(도배 한 번에 경보가 쏟아지지 않게) */
class InquiryAlertKind(override val minInterval: Duration) : AlertKind {
    override val name: String = "INQUIRY_RECEIVED"
    override val severity: AlertSeverity = AlertSeverity.INFO
    override val title: String = "A new inquiry was received"
}

/**
 * `alert` 모듈이 있으면(`OwnerAlerts` 빈) 문의가 들어올 때 주인에게 알린다. inquiry 는 alert 를 `compileOnly` 로만 안다.
 * 경보에는 문의 번호 · 종류만 싣는다 - 본문 · 답장 이메일은 싣지 않는다.
 */
@AutoConfiguration(before = [InquiryAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.alert.OwnerAlerts"])
@ConditionalOnBean(OwnerAlerts::class)
class InquiryAlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = ["inquiryOwnerAlert"])
    fun inquiryOwnerAlert(alerts: OwnerAlerts, properties: InquiryProperties): InquiryListener {
        val kind = InquiryAlertKind(properties.alertInterval)
        return InquiryListener { inquiry ->
            alerts.emit(kind, "inquiry", "inquiry #${inquiry.id} (${inquiry.kind.name}) received")
        }
    }
}
