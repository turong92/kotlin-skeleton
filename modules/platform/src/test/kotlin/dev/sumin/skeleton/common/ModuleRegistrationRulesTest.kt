package dev.sumin.skeleton.common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 모듈은 빈을 AutoConfiguration 으로만 등록한다. 앱의 component scan 이 모듈 패키지에 닿으면 안 되고,
 * 그래서 모듈 main 의 클래스는 스캔 대상 스테레오타입(@Component · @Service · @Configuration 등)을 달지 않는다.
 *
 * 유일한 예외는 Spring MVC 가 애너테이션으로만 찾는 핸들러 표지(@RestController · @Controller · @ControllerAdvice ·
 * @RestControllerAdvice)다. 클래스 레벨 @RequestMapping 만으로는 핸들러로 인식되지 않는다(Spring 6+, 실측: 404).
 * 이 표지는 같은 모듈의 AutoConfiguration 이 그 클래스를 @Bean 으로 등록할 때만 허용한다.
 *
 * 소스를 텍스트로 훑는다 — 모듈이 늘어도 이 테스트는 그대로 그 모듈을 검사한다.
 */
class ModuleRegistrationRulesTest {
    private val repoRoot: Path =
        Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })

    @Test
    fun `no module main class carries a component scanning stereotype`() {
        val violations = moduleMainSources().flatMap { file ->
            annotationHits(file).filter { it.annotation !in MVC_HANDLER_MARKERS }
                .map { "${repoRoot.relativize(file)}:${it.line} @${it.annotation}" }
        }
        assertEquals(emptyList(), violations, "모듈 빈은 AutoConfiguration 의 @Bean 으로 등록한다 (docs/minimal-composition.md)")
    }

    @Test
    fun `an MVC handler marker is allowed only when the module's AutoConfiguration registers that class as a bean`() {
        val violations = moduleMainSources().flatMap { file ->
            val module = repoRoot.relativize(file).subpath(0, 2) // modules/<name>
            val autoConfigurations = moduleMainSources().filter {
                repoRoot.relativize(it).startsWith(module) && it.name.endsWith("AutoConfiguration.kt")
            }.joinToString("\n") { Files.readString(it) }
            annotationHits(file).filter { it.annotation in MVC_HANDLER_MARKERS }.mapNotNull { hit ->
                val className = Regex("""\bclass\s+(\w+)""").find(file.readLines().drop(hit.line).joinToString("\n"))?.groupValues?.get(1)
                val registered = className != null && Regex("""\)\s*:\s*$className\b""").containsMatchIn(autoConfigurations)
                if (registered) null else "${repoRoot.relativize(file)}:${hit.line} @${hit.annotation} $className has no @Bean in ${module}'s AutoConfiguration"
            }
        }
        assertEquals(emptyList(), violations)
    }

    private data class Hit(val line: Int, val annotation: String)

    private fun annotationHits(file: Path): List<Hit> =
        file.readLines().mapIndexedNotNull { index, line ->
            val code = line.substringBefore("//").trim()
            if (code.startsWith("*") || code.startsWith("/*")) return@mapIndexedNotNull null
            SCANNING_STEREOTYPES.firstOrNull { Regex("(?<![\\w.])@$it(?!\\w)").containsMatchIn(code) }
                ?.let { Hit(index + 1, it) }
        }

    private fun moduleMainSources(): List<Path> =
        Files.list(repoRoot.resolve("modules")).use { modules ->
            modules.toList().flatMap { module ->
                val main = module.resolve("src/main")
                if (!Files.isDirectory(main)) emptyList()
                else Files.walk(main).use { walk -> walk.filter { it.name.endsWith(".kt") }.toList() }
            }
        }

    private companion object {
        val MVC_HANDLER_MARKERS = setOf("RestController", "Controller", "ControllerAdvice", "RestControllerAdvice")
        val SCANNING_STEREOTYPES = listOf(
            "Component", "Service", "Repository", "Configuration",
            "ComponentScan", "ConfigurationPropertiesScan", "SpringBootApplication", "SpringBootConfiguration",
        ) + MVC_HANDLER_MARKERS
    }
}
