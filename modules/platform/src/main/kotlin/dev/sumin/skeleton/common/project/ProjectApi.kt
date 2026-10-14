package dev.sumin.skeleton.common.project

import org.slf4j.LoggerFactory
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.servlet.mvc.condition.AbstractRequestCondition
import org.springframework.web.servlet.mvc.method.RequestMappingInfo
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import jakarta.servlet.http.HttpServletRequest

/**
 * "이 프로젝트 전용" 컨트롤러 표시. [value] 는 `skeleton.projects.<이름>` 의 이름이다. 다른 프로젝트 도메인으로 부르면 짝이 안 맞아 스프링이 404 를 낸다
 * (컨트롤러 안의 if 가 아니다). 두 프로젝트가 같은 경로를 써도 기동 충돌이 없다. 프로젝트 목록이 비어 있으면(한 서비스) 표시는 무시되고 전부 열린다.
 *
 * **메타 어노테이션으로 쓴다**: 프로젝트 모듈이 `@ProjectApi("ovation") annotation class OvationApi` 를 한 번 만들고 컨트롤러에는 `@OvationApi` 만 붙인다.
 * 목록에 없는 이름이 붙은 컨트롤러는 어느 도메인에서도 열리지 않고 기동 로그에 경고가 남는다(빌드에는 있지만 이 서버에서 안 켠 프로젝트일 수 있다).
 */
@Target(AnnotationTarget.CLASS, AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class ProjectApi(val value: String)

/** 지금 프로젝트가 [name] 일 때만 맞는 요청 조건 */
class ProjectCondition(private val name: String) : AbstractRequestCondition<ProjectCondition>() {
    override fun getContent(): Collection<String> = listOf(name)

    override fun getToStringInfix(): String = " && "

    override fun combine(other: ProjectCondition): ProjectCondition = this

    override fun getMatchingCondition(request: HttpServletRequest): ProjectCondition? =
        if (ProjectContext.currentOrNull()?.id == name) this else null

    override fun compareTo(other: ProjectCondition, request: HttpServletRequest): Int = 0
}

/**
 * [ProjectApi] 가 붙은 컨트롤러의 매핑에 [ProjectCondition] 을 얹는다 — 프로젝트 목록이 있을 때만 쓰인다.
 * (`getCustomTypeCondition` 은 클래스에 `@RequestMapping` 이 있을 때만 쓰이므로, 메서드 매핑마다 얹는다)
 */
class ProjectApiHandlerMapping(private val registry: ProjectRegistry) : RequestMappingHandlerMapping() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<Class<*>>()

    override fun getMappingForMethod(method: java.lang.reflect.Method, handlerType: Class<*>): RequestMappingInfo? {
        val info = super.getMappingForMethod(method, handlerType) ?: return null
        val name = AnnotatedElementUtils.findMergedAnnotation(handlerType, ProjectApi::class.java)?.value ?: return info
        if (registry.projects.none { it.id == name } && warned.add(handlerType)) {
            log.warn("{} is marked for project '{}', which is not in skeleton.projects — it opens on no domain of this server", handlerType.name, name)
        }
        return RequestMappingInfo.paths().customCondition(ProjectCondition(name)).build().combine(info)
    }
}
