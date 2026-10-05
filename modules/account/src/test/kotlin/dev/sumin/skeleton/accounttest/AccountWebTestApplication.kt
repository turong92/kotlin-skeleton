package dev.sumin.skeleton.accounttest

import dev.sumin.skeleton.account.RecordingMailer
import dev.sumin.skeleton.account.abuse.AccountTaskRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
// job-queue-jdbc(잡 연동 시험용)가 시험 클래스패스에 spring-jdbc 를 끌고 오므로 DataSource 자동설정은 끈다 — 이 시험들은 DB 가 필요 없다
@SpringBootApplication(excludeName = ["org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"])
class AccountWebTestApplication

/** 메일은 기록하고 · 뒤로 넘기는 일은 그 자리에서 — 응답 직후 결과를 볼 수 있게 */
@TestConfiguration(proxyBeanMethods = false)
class AccountTestBeans {
    @Bean fun recordingMailer(): RecordingMailer = RecordingMailer()
    @Bean fun accountTaskRunner(): AccountTaskRunner = AccountTaskRunner.DIRECT
}
