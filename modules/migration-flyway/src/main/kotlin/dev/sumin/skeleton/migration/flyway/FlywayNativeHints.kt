package dev.sumin.skeleton.migration.flyway

import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지에서 Flyway 가 돌려면 이 레포가 더해야 하는 것 (docs/native-image.md). `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 *  · `db/migration` 아래 모든 파일(SQL) — 앱과 모듈(`job-queue-jdbc` · `payment-jdbc` …)의 마이그레이션이 이 한 경로에 합류한다. 새 모듈이 다른 경로에 마이그레이션을 두면 여기에도 적는다
 *  · Flyway 가 읽는 자기 리소스(version.txt · 플러그인 목록)
 *  · Flyway 가 이름으로 만드는 로그 생성기 — 플러그인의 metadata 저장소는 Flyway 11 까지만 알아서(`excludedModules`) Flyway 12 의 jar 자체 설정에 이것이 빠져 있다
 */
class FlywayNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.resources().registerPattern("db/migration/*/*")
        hints.resources().registerPattern("db/migration/*")
        hints.resources().registerPattern("org/flywaydb/core/internal/version.txt")   // 시작할 때 읽는 버전 표시
        hints.resources().registerPattern("META-INF/services/org.flywaydb.*")          // 플러그인 목록(ServiceLoader)
        hints.reflection().registerTypeIfPresent(classLoader, "org.flywaydb.core.internal.logging.slf4j.Slf4jLogCreator", MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
    }
}
