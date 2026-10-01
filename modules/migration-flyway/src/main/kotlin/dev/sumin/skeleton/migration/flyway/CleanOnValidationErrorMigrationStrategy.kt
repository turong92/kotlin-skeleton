package dev.sumin.skeleton.migration.flyway

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy

/**
 * 로컬 전용. 검증이 실패하면(이미 적용된 파일을 고쳐 체크섬이 다르거나, 적용된 파일이 사라짐) clean 후 다시 migrate.
 * 미적용(pending) 마이그레이션과 앱의 ignore-migration-patterns(기본 `*:future`) 에 걸리는 것은 정상 상황이므로 clean 하지 않는다.
 * 앱이 out-of-order 를 켜지 않았으면 늦게 합쳐진 이른 시각 파일이 IGNORED 가 되어 migrate() 자체가 실패하는 상황이고,
 * 이 전략은 그때 clean 한다.
 * Flyway 12 에는 cleanOnValidationError 가 없어서 전략으로 구현한다. Boot 의 clean-disabled 기본값(true)을 건드리지 않고
 * clean 할 때만 같은 설정에 cleanDisabled(false) 를 얹은 Flyway 를 따로 만든다.
 */
class CleanOnValidationErrorMigrationStrategy : FlywayMigrationStrategy {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun migrate(flyway: Flyway) {
        val validation = Flyway.configure(flyway.configuration.classLoader)
            .configuration(flyway.configuration)
            // 앱의 패턴(기본 *:future — 다른 브랜치가 적용한, 여기 없는 파일)을 지키고 pending 만 더한다. 대체하면 브랜치 전환만으로 DB 를 민다
            .ignoreMigrationPatterns(*(flyway.configuration.ignoreMigrationPatterns.map { it.toString() } + "*:pending").toTypedArray())
            .load()
            .validateWithResult()
        if (!validation.validationSuccessful) {
            log.warn(
                "Flyway validation failed ({}); cleaning the local database and re-applying migrations",
                validation.invalidMigrations.joinToString { "${it.version}: ${it.errorDetails?.errorMessage}" },
            )
            Flyway.configure(flyway.configuration.classLoader)
                .configuration(flyway.configuration)
                .cleanDisabled(false)
                .load()
                .clean()
        }
        flyway.migrate()
    }
}
