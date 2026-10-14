package dev.sumin.skeleton.common.project

/** 지금 스레드가 어느 프로젝트의 일을 하는지 — 요청 필터([ProjectFilter])가 정하고, 요청 밖에서는 [runIn] 으로만 정한다 */
object ProjectContext {
    private val holder = ThreadLocal<Project?>()

    fun currentOrNull(): Project? = holder.get()

    /** 프로젝트 없이 부르면 예외 — 조용히 기본 프로젝트로 가지 않는다 */
    fun current(): Project = holder.get() ?: throw NoProjectContextException()

    fun <T> runIn(project: Project, block: () -> T): T {
        val previous = holder.get()
        holder.set(project)
        try {
            return block()
        } finally {
            if (previous == null) holder.remove() else holder.set(previous)
        }
    }
}

class NoProjectContextException :
    IllegalStateException(
        "No project in context. A request gets its project from its Host (skeleton.projects.<id>.hosts); " +
            "work outside a request must say which project it is for: ProjectContext.runIn(project) { ... }.",
    )

/** 프로젝트마다 한 번 만들어 두는 부품 — 처음 쓸 때 그 프로젝트의 설정으로 만든다. 지금 프로젝트는 [ProjectContext] 가 정한다 */
class ProjectScoped<T : Any>(private val create: (Project) -> T) {
    private val parts = java.util.concurrent.ConcurrentHashMap<String, T>()

    fun get(): T = of(ProjectContext.current())

    fun of(project: Project): T = parts.computeIfAbsent(project.id) { create(project) }
}
