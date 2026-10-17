package dev.sumin.skeleton.board.alert

import dev.sumin.skeleton.alert.AlertKind
import dev.sumin.skeleton.alert.AlertSeverity
import dev.sumin.skeleton.alert.OwnerAlerts
import dev.sumin.skeleton.board.BoardAutoConfiguration
import dev.sumin.skeleton.board.BoardPublicSyncAlerts
import java.time.Duration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/** board 가 스스로 내는 경보 종류 */
enum class BoardAlertKind(override val severity: AlertSeverity, override val title: String, override val minInterval: Duration) : AlertKind {
    PUBLIC_ATTACHMENT_STUCK(AlertSeverity.WARN, "A public picture cannot be brought in step with the board (retries keep failing)", Duration.ofHours(6)),
}

/** `alert` 모듈이 있으면(`OwnerAlerts` 빈) 공개 영역 조정이 계속 실패할 때 주인에게 알린다. board 는 alert 를 `compileOnly` 로만 안다 */
@AutoConfiguration(before = [BoardAutoConfiguration::class], afterName = ["dev.sumin.skeleton.alert.AlertAutoConfiguration"])
@ConditionalOnClass(name = ["dev.sumin.skeleton.alert.OwnerAlerts"])
@ConditionalOnBean(OwnerAlerts::class)
class BoardAlertAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    fun boardPublicSyncAlerts(alerts: OwnerAlerts): BoardPublicSyncAlerts =
        BoardPublicSyncAlerts { what, attempts, error -> alerts.emit(BoardAlertKind.PUBLIC_ATTACHMENT_STUCK, key = what, detail = "failed $attempts times in a row: $error") }
}
