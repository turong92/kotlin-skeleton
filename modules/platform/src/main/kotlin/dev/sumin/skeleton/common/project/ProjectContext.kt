package dev.sumin.skeleton.common.project

/** 지금 스레드가 어느 프로젝트의 일을 하는지 — 요청 필터([ProjectFilter])가 정하고, 요청 밖에서는 [runIn] 으로만 정한다 */
object ProjectContext {
    private val holder = ThreadLocal<Project?>()

    fun currentOrNull(): Project? = holder.get()

    /** 프로젝트 없이 부르면 예외 — 조용히 기본 프로젝트로 가지 않는다 */
    fun current(): Project = holder.get() ?: throw NoProjectContextException()

    /**
     * 프로세스 메모리에 쌓는 상태(요청 한도 · 멱등 · 알림 · 디바운스 …)의 키 — 프로젝트 안이면 프로젝트를 앞에 붙여, 계정 id · IP 가 프로젝트끼리 겹쳐도 상태가 섞이지 않게 한다.
     * 프로젝트 밖(한 서비스, 목록이 빈 앱)이면 키 그대로라 지금과 완전히 같다. 프로젝트 id 에는 `|` 가 들지 못해 앞부분이 모호하지 않다
     */
    fun scopedKey(key: String): String = holder.get()?.let { "${it.id}|$key" } ?: key

    /** 다른 스레드로 넘길 일에 지금 프로젝트를 들려 보낸다 — 스레드가 프로젝트를 물려받지는 않는다. 프로젝트 밖이면 일을 그대로 돌려준다 */
    fun carry(task: Runnable): Runnable {
        val project = holder.get() ?: return task
        return Runnable { runIn(project) { task.run() } }
    }

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

/** 다른 스레드(MVC 비동기 실행기 …)로 넘어가는 일에 제출한 쪽의 프로젝트를 들려 보낸다 — 스레드가 프로젝트를 물려받지는 않는다(InheritableThreadLocal 아님) */
class ProjectTaskDecorator : org.springframework.core.task.TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable = ProjectContext.carry(runnable)

    companion object {
        /** 프로젝트를 들려 보내는 데코레이터가 [others](앱의 것)를 안에 두고 함께 돈다 — 제출한 스레드에서 모두 호출되고, 실행 스레드에서는 프로젝트가 바깥이다 */
        fun around(others: List<org.springframework.core.task.TaskDecorator>): org.springframework.core.task.TaskDecorator =
            org.springframework.core.task.TaskDecorator { runnable -> ProjectContext.carry(others.fold(runnable) { task, other -> other.decorate(task) }) }
    }
}
