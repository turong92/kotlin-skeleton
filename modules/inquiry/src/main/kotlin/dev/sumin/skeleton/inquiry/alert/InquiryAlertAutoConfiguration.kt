package dev.sumin.skeleton.inquiry.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.inquiry.InquiryAutoConfiguration
import dev.sumin.skeleton.inquiry.InquiryListener
import java.time.Duration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * inquiry 가 스스로 내는 경보 종류 — 키는 고정 하나라 최소 간격(기본 10분) 안의 문의는 경보 한 줄로 접힌다(도배 한 번에 경보가 쏟아지지 않게).
 * 간격을 바꾸려면 기존 `skeleton.alert.intervals.INQUIRY_RECEIVED` 로 덮어쓴다.
 */
enum class InquiryAlertKind(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration) : AlertKind {
    INQUIRY_RECEIVED(AlertSeverity.INFO, "A new inquiry was received", Duration.ofMinutes(10)),
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
    fun inquiryOwnerAlert(alerts: OwnerAlerts): InquiryListener = InquiryListener { inquiry ->
        alerts.emit(InquiryAlertKind.INQUIRY_RECEIVED, "inquiry", "inquiry #${inquiry.id} (${inquiry.kind.name}) received")
    }
}
