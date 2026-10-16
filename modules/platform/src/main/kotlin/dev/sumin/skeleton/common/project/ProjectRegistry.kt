package dev.sumin.skeleton.common.project

import java.util.Locale
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.env.Environment

/**
 * `skeleton.projects.<id>.{hosts, schema, datasource.{username, password, pool-max}, config.<skeleton 아래 키>}` 로 적은 프로젝트 목록.
 * **목록이 비어 있으면 이 레지스트리는 만들어지지 않고** 앱은 지금까지와 완전히 같게 동작한다.
 * 같은 Host 를 두 프로젝트가 쓰거나 스키마가 겹치거나 모양이 틀리면 기동이 실패한다.
 */
class ProjectRegistry private constructor(
    val projects: List<Project>,
    private val environment: Environment,
) {
    /** 서비스 중인 프로젝트 — DB 풀 · 마이그레이션 · 뒤 작업 · "둘 이상" 개수는 이것만 센다. 닫힌 프로젝트는 도메인만 남는다 */
    val active: List<Project> = projects.filter { !it.closed }

    private val byHost = projects.flatMap { p -> p.hosts.map { it to p } }.toMap()

    /** 요청의 Host 로 프로젝트를 정한다 — 소문자로, 포트는 빼고 비교한다(`acme.localhost:5173` == `acme.localhost`). 모르는 도메인은 null */
    fun byHost(host: String?): Project? =
        host?.trim()?.lowercase(Locale.ROOT)?.let { if (it.startsWith("[")) it else it.substringBefore(':') }?.let(byHost::get)

    /**
     * 공통 `skeleton.*` 위에 이 프로젝트의 `config` 를 덮어 [type] 으로 바인딩한다 — 모듈이 쓰는 properties 클래스를 그대로.
     * [prefix] 는 `skeleton.auth` 처럼 전체 접두사.
     */
    fun <T : Any> bind(project: Project, prefix: String, type: Class<T>): T {
        val overlay = MapConfigurationPropertySource(project.config.mapKeys { "$ROOT.${it.key}" })
        val sources = listOf(overlay) + ConfigurationPropertySources.get(environment)
        return Binder(sources, PropertySourcesPlaceholdersResolver(environment)).bindOrCreate(prefix, Bindable.of(type))
    }

    /** 공통 값 **없이** 이 프로젝트의 `config` 만으로 바인딩한다 — 공통 값으로 내려가면 안 되는 값(메일 링크 주소 …)이 프로젝트에 정해져 있는지 볼 때 */
    fun <T : Any> bindOwn(project: Project, prefix: String, type: Class<T>): T =
        Binder(listOf(MapConfigurationPropertySource(project.config.mapKeys { "$ROOT.${it.key}" })), PropertySourcesPlaceholdersResolver(environment)).bindOrCreate(prefix, Bindable.of(type))

    /** 프로젝트마다 한 번 만들어 두는 부품 도우미 — [bind] 한 properties 로 부품을 만든다 */
    fun <P : Any, T : Any> scopedFrom(prefix: String, type: Class<P>, create: (Project, P) -> T): ProjectScoped<T> =
        ProjectScoped { project -> create(project, bind(project, prefix, type)) }

    /** 요청 밖의 일: 모든 프로젝트에서 차례로 */
    fun forEachProject(block: (Project) -> Unit) = active.forEach { ProjectContext.runIn(it) { block(it) } }

    companion object {
        // 설정 접두사는 `skeleton.projects` 한 곳에만 적는다 — 찍은 프로젝트에서 rename-skeleton.sh 가 `skeleton.<글자>` 꼴을 새 접두사로 바꾸므로, 루트는 여기서 떼어 쓴다
        // (`"skeleton"` 이나 `"skeleton.${…}"` 로 따로 적으면 안 바뀌어 찍은 프로젝트에서 프로젝트 목록을 못 읽는다)
        const val PROPERTY = "skeleton.projects"
        val ROOT: String get() = PROPERTY.substringBefore('.')
        private val ID = Regex("[a-z][a-z0-9_-]{0,39}")
        private val SCHEMA = Regex("[a-z][a-z0-9_]{0,62}")
        private val HOST = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?")

        /** 목록에 프로젝트가 하나라도 있는가 — 자동 구성의 켜짐 조건 */
        fun configured(environment: Environment): Boolean = raw(environment).isNotEmpty()

        /** [supportedConfig] 가 있으면 프로젝트 `config` 키는 그 접두사들 아래여야 한다 — 지원하지 않는 키가 조용히 무시되지 않게 기동 실패 */
        fun from(environment: Environment, supportedConfig: List<String>? = null): ProjectRegistry {
            val raw = raw(environment)
            val projects = raw.toSortedMap().map { (id, s) -> project(id, s, supportedConfig) }
            check(projects.isNotEmpty()) { "$PROPERTY is empty" }
            projects.filter { it.schema.isNotEmpty() }.groupBy { it.schema }.filterValues { it.size > 1 }.forEach { (schema, same) ->
                error("Projects ${same.joinToString { "'${it.id}'" }} use the same schema '$schema' — every project needs its own ($PROPERTY.<id>.schema)")
            }
            projects.flatMap { p -> p.hosts.map { it to p.id } }.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }.forEach { (host, ids) ->
                error("Projects ${ids.joinToString { "'$it'" }} use the same host '$host' — a host belongs to one project ($PROPERTY.<id>.hosts)")
            }
            return ProjectRegistry(projects, environment)
        }

        /** 키 비교용 — 대소문자 · 하이픈 · 인덱스를 무시하고 마디는 점으로 */
        private fun configKey(key: String): String = key.lowercase(Locale.ROOT).replace("-", "").replace("_", "").replace(Regex("\\[[^\\]]*]"), "")

        private fun raw(environment: Environment): Map<String, Settings> =
            Binder.get(environment).bind(PROPERTY, Bindable.mapOf(String::class.java, Settings::class.java)).orElse(emptyMap<String, Settings>()) ?: emptyMap()

        private fun project(id: String, s: Settings, supportedConfig: List<String>?): Project {
            val where = "$PROPERTY.$id"
            check(ID.matches(id)) { "Project id '$id' must be lower-case letters, digits, '-' or '_' and start with a letter ($PROPERTY.<id>)" }
            supportedConfig?.let { allowed ->
                s.config.keys.filterNot { key -> allowed.any { configKey(key).startsWith(configKey(it)) } }.let { bad ->
                    check(bad.isEmpty()) { "$where.config has keys that are not per-project settings (they would be silently ignored): ${bad.sorted()}. Supported: ${allowed.sorted()}" }
                }
            }
            val hosts = s.hosts.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
            check(hosts.isNotEmpty()) { "$where.hosts is empty — a project is found by its domain" }
            hosts.forEach { check(HOST.matches(it)) { "$where.hosts has '$it', which is not a bare host name (no port, scheme or path)" } }
            val schema = s.schema.trim().lowercase(Locale.ROOT)
            // 닫힌 프로젝트는 DB 를 쓰지 않으므로 스키마를 안 적어도 된다 (적었으면 모양은 본다)
            check(SCHEMA.matches(schema) || (s.status == ProjectStatus.CLOSED && schema.isEmpty())) { "$where.schema must be a plain lower-case identifier (letters, digits, '_'), was '${s.schema}'" }
            check(s.datasource.poolMax >= 1) { "$where.datasource.pool-max must be at least 1" }
            return Project(id, hosts.distinct(), schema, s.datasource, s.config, s.status)
        }
    }

    /** 바인딩 대상 (`skeleton.projects.<id>`) */
    class Settings(
        val hosts: List<String> = emptyList(),
        val schema: String = "",
        val datasource: ProjectDatasource = ProjectDatasource(),
        val config: Map<String, String> = emptyMap(),
        val status: ProjectStatus = ProjectStatus.ACTIVE,
    )
}
