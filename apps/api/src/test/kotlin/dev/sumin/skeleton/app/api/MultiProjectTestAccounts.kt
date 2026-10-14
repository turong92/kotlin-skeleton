package dev.sumin.skeleton.app.api

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails

/**
 * 프로젝트 모드 시험의 DB — 새 데이터베이스 하나 + 프로젝트마다 **계정 · 스키마 · 계정의 기본 search_path**.
 * 로컬(`scripts/dev-multi.sh`)과 운영 문서가 쓰는 것과 같은 SQL(`scripts/project-account.sql`)로 만든다. 계정 이름은 스키마 이름이고 전역이라 시험끼리 겹치지 않게 스키마 이름이 유일해야 한다.
 */
object MultiProjectTestAccounts {
    private val template: String by lazy { Files.readString(Path.of("../../scripts/project-account.sql")) }

    fun password(schema: String) = "$schema-pw"

    fun sql(user: String, password: String, schema: String): String =
        template.replace("{{user}}", user).replace("{{password}}", password).replace("{{schema}}", schema)

    fun execute(admin: JdbcConnectionDetails, sql: String) {
        DriverManager.getConnection(admin.jdbcUrl, admin.username, admin.password).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /** 새 데이터베이스 + [schemas] 마다 계정(`<스키마>` / `<스키마>-pw`)을 만든다. [passwordOf] 로 비밀번호를 바꿀 수 있다(로컬 yml 의 값과 맞출 때) */
    fun newDatabase(vararg schemas: String, passwordOf: (String) -> String = ::password): JdbcConnectionDetails =
        SharedPostgres.newDatabase().also { admin -> schemas.forEach { execute(admin, sql(it, passwordOf(it), it)) } }

    /** `skeleton.projects.<id>` 의 hosts · schema · datasource 속성 */
    fun project(id: String, host: String, schema: String, password: String = password(schema)): List<String> = listOf(
        "skeleton.projects.$id.hosts=$host", "skeleton.projects.$id.schema=$schema",
        "skeleton.projects.$id.datasource.username=$schema", "skeleton.projects.$id.datasource.password=$password",
    )
}
