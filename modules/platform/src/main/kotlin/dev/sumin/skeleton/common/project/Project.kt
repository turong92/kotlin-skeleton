package dev.sumin.skeleton.common.project

/**
 * 한 프로세스 안의 서비스 하나(프로젝트). 부품(빈)은 프로젝트 수와 상관없이 한 벌이고, 프로젝트마다 다른 것은 이 값 — 도메인 · DB 스키마 · 덮어쓸 설정 — 뿐이다.
 * 설정은 `skeleton.projects.<id>` 에 적는다 ([ProjectRegistry]). 목록이 비어 있으면 프로젝트라는 개념이 없고 앱은 지금까지처럼 서비스 하나다.
 */
data class Project(
    val id: String,
    /** 이 프로젝트의 도메인들 (소문자, 포트 없음). 요청의 Host 로 프로젝트를 정한다 */
    val hosts: List<String>,
    /** DB 스키마 (MySQL 에서는 데이터베이스) 이름 — 프로젝트마다 하나, 겹치지 않는다 */
    val schema: String,
    val datasource: ProjectDatasource,
    /** `skeleton.` 아래 키 → 값. 공통 `skeleton.*` 위에 덮어 같은 properties 클래스로 바인딩한다 ([ProjectRegistry.bind]) */
    val config: Map<String, String>,
    /** `closed` 면 서비스를 끝낸 프로젝트 — 도메인은 503 으로 답하고 DB 풀 · 마이그레이션 · 개수 계산에서 빠진다 */
    val status: ProjectStatus = ProjectStatus.ACTIVE,
) {
    val closed: Boolean get() = status == ProjectStatus.CLOSED
}

enum class ProjectStatus { ACTIVE, CLOSED }

/** 프로젝트의 DB 연결 — 비워 두면 공통 `spring.datasource.*` 를 쓴다 (프로젝트별 DB 계정을 주면 자기 스키마 밖은 DB 가 막는다) */
data class ProjectDatasource(
    val username: String? = null,
    val password: String? = null,
    /** 이 프로젝트 연결 풀의 최대 연결 수. 안 쓰는 프로젝트는 연결 0개이고, 프로젝트가 여럿이라 기본을 작게 둔다 */
    val poolMax: Int = DEFAULT_POOL_MAX,
) {
    companion object {
        const val DEFAULT_POOL_MAX = 3
    }
}
