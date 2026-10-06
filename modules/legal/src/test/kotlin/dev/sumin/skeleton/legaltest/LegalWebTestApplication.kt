package dev.sumin.skeleton.legaltest

import dev.sumin.skeleton.legal.ConsentStore
import dev.sumin.skeleton.legal.FakeConsentStore
import dev.sumin.skeleton.legal.FakeLedger
import dev.sumin.skeleton.legal.LegalLedger
import dev.sumin.skeleton.legal.MutableClock
import java.time.Instant
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

// 모듈 패키지 밖에 둔다 — 모듈 클래스는 컴포넌트 스캔이 아니라 AutoConfiguration 으로만 들어온다
@SpringBootApplication
class LegalWebTestApplication

@TestConfiguration(proxyBeanMethods = false)
class LegalWebFakes {
    @Bean fun clock() = MutableClock(Instant.parse("2026-10-07T00:00:00Z"))
    @Bean fun store() = FakeConsentStore()
    @Bean fun consentStore(store: FakeConsentStore): ConsentStore = store
    @Bean fun ledger(): LegalLedger = FakeLedger()
}

/** 재동의 필터 시험용 — 보호된 경로와 필터가 건드리지 않는 경로 */
@RestController
class ProbeController {
    @GetMapping("/api/v1/things") fun things() = mapOf("ok" to true)
    @GetMapping("/api/v1/auth/probe") fun auth() = mapOf("ok" to true)
    @GetMapping("/api/v1/account/probe") fun account() = mapOf("ok" to true)
    @GetMapping("/elsewhere/probe") fun elsewhere() = mapOf("ok" to true)
}
