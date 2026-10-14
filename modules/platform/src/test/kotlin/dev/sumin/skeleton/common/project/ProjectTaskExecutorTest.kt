package dev.sumin.skeleton.common.project

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.core.task.TaskDecorator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** MVC 비동기 실행기: 프로젝트를 들려 보내는 데코레이터가 앱 자신의 `TaskDecorator`(MDC · trace)를 덮어쓰지 않고 함께 돈다 */
class ProjectTaskExecutorTest {
    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ProjectAutoConfiguration::class.java, TaskExecutionAutoConfiguration::class.java))
        .withPropertyValues("skeleton.projects.a.hosts[0]=a.test", "skeleton.projects.a.schema=pa")

    @Configuration(proxyBeanMethods = false)
    class AppDecorators {
        @Bean fun mdcDecorator(): TaskDecorator = TaskDecorator { task -> val trace = MDC.get("trace"); Runnable { MDC.put("trace", trace); try { task.run() } finally { MDC.remove("trace") } } }
    }

    private data class Seen(val project: String?, val trace: String?)

    @Test
    fun `the project and the app's own task decorator both apply to the same task`() {
        runner.withUserConfiguration(AppDecorators::class.java).run { context ->
            val executor = context.getBean(AsyncTaskExecutor::class.java)
            val project = context.getBean(ProjectRegistry::class.java).projects.single()
            val seen = CompletableFuture<Seen>()

            MDC.put("trace", "t-1")
            try {
                ProjectContext.runIn(project) { executor.execute { seen.complete(Seen(ProjectContext.currentOrNull()?.id, MDC.get("trace"))) } }
            } finally {
                MDC.remove("trace")
            }

            assertEquals(Seen("a", "t-1"), seen.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `without an app decorator the project still travels`() {
        runner.run { context ->
            val executor = context.getBean(AsyncTaskExecutor::class.java)
            val project = context.getBean(ProjectRegistry::class.java).projects.single()
            val seen = CompletableFuture<String?>()

            ProjectContext.runIn(project) { executor.execute { seen.complete(ProjectContext.currentOrNull()?.id) } }

            assertEquals("a", seen.get(5, TimeUnit.SECONDS))
        }
    }
}
