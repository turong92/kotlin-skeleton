package dev.sumin.skeleton.app.workbench

import dev.sumin.skeleton.config.aws.ssm.AwsSsmProperties
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.jvmErasure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.bind.BindContext
import org.springframework.boot.context.properties.bind.BindHandler
import org.springframework.boot.context.properties.source.ConfigurationPropertyName
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.env.MapPropertySource
import org.springframework.boot.context.properties.source.ConfigurationPropertySource
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.env.PropertySource
import org.springframework.core.io.FileSystemResource
import org.springframework.core.type.filter.AnnotationTypeFilter

/**
 * `docs/config/modules/<module>.yml` 은 프로젝트가 복사해 가는 설정 블록이다. 코드와 어긋나면 안 된다:
 *  - 주석 아닌 키는 모두 모듈의 `@ConfigurationProperties` 에 바인딩되고 (오타 · 없는 키는 실패)
 *  - 그 값은 기본값과 같다 (복사만 해서는 동작이 바뀌지 않는다)
 *  - 모듈 속성의 모든 잎 키가 스니펫에 (주석으로라도) 나온다
 */
class ModuleConfigSnippetsTest {
    private val repoRoot: Path =
        Path.of(requireNotNull(System.getProperty("skeleton.repoRoot")) { "skeleton.repoRoot system property" })
    private val snippetDir = repoRoot.resolve("docs/config/modules")

    private val propertyClasses: Map<String, KClass<*>> by lazy {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(ConfigurationProperties::class.java))
        scanner.findCandidateComponents("dev.sumin.skeleton")
            .map { Class.forName(it.beanClassName).kotlin }
            .associateBy { requireNotNull(it.java.getAnnotation(ConfigurationProperties::class.java)).let { a -> a.prefix.ifBlank { a.value } } } +
            // config-aws-ssm 은 EnvironmentPostProcessor 라서 빈 없이 Binder 로 직접 읽는다 (@ConfigurationProperties 아님)
            ("skeleton.config.aws.ssm" to AwsSsmProperties::class)
    }

    @Test
    fun `snippets exist`() {
        assertTrue(Files.isDirectory(snippetDir), "docs/config/modules is missing")
        assertTrue(snippets().size >= 20, "found ${snippets().map { it.name }}")
    }

    @Test
    fun `uncommented snippet keys bind to module properties and equal the defaults`() {
        snippets().forEach { snippet ->
            val source = yaml(snippet)
            val names = source.propertyNames.toList()
            val binder = binderOf(source)
            val empty = binderOf(MapPropertySource("empty", emptyMap<String, Any>()))

            // 스니펫이 건드리는 접두사의 클래스를 모두 찾아 비교한다
            val touched = propertyClasses.filterKeys { prefix -> names.any { it == prefix || it.startsWith("$prefix.") } }
            assertTrue(touched.isNotEmpty(), "${snippet.name}: no @ConfigurationProperties for its keys")

            val consumed = RecordingBindHandler()
            touched.forEach { (prefix, type) ->
                val bound = binder.bindOrCreate(prefix, Bindable.of(type.java), consumed)
                val defaults = empty.bindOrCreate(prefix, Bindable.of(type.java))
                assertEquals(defaults, bound, "${snippet.name}: values under '$prefix' differ from the module defaults")
            }
            // 바인딩되지 않은 키(오타 · 없는 키)가 없어야 한다
            val unbound = names.filterNot { consumed.claims(it) }
            assertEquals(emptyList(), unbound, "${snippet.name}: keys that bind to nothing")
        }
    }

    @Test
    fun `every property of the module appears in its snippet`() {
        val missing = snippets().flatMap { snippet ->
            val text = snippet.readText()
            val source = yaml(snippet)
            val prefixes = propertyClasses.filterKeys { prefix -> source.propertyNames.toList().any { it == prefix || it.startsWith("$prefix.") } }
            prefixes.flatMap { (prefix, type) ->
                leafKeys(type).filter { key -> !Regex("(^|[\\s#])${Regex.escape(key)}:").containsMatchIn(text) }
                    .map { "${snippet.name}: $prefix.$it" }
            }
        }
        assertEquals(emptyList(), missing)
    }

    private fun snippets(): List<Path> =
        if (!Files.isDirectory(snippetDir)) emptyList()
        else Files.list(snippetDir).use { it.filter { f -> f.name.endsWith(".yml") }.sorted().toList() }

    private fun binderOf(source: PropertySource<*>): Binder {
        val sources: List<ConfigurationPropertySource> = ConfigurationPropertySources.from(source).filterNotNull()
        return Binder(sources)
    }

    private fun yaml(path: Path): EnumerablePropertySource<*> {
        val loaded = YamlPropertySourceLoader().load(path.name, FileSystemResource(path))
        return (loaded.firstOrNull() ?: MapPropertySource(path.name, emptyMap())) as EnumerablePropertySource<*>
    }

    /** 바인딩에 성공한 이름을 모은다. Map · Collection 으로 묶인 것은 그 아래 키를 모두 흡수한다. */
    private class RecordingBindHandler : BindHandler {
        private val scalars = mutableSetOf<String>()
        private val containers = mutableSetOf<String>()

        override fun onSuccess(name: ConfigurationPropertyName, target: Bindable<*>, context: BindContext, result: Any): Any? {
            val type = target.type.resolve(Any::class.java)
            if (Map::class.java.isAssignableFrom(type) || Collection::class.java.isAssignableFrom(type) || type.isArray) containers += name.toString()
            else scalars += name.toString()
            return result
        }

        fun claims(key: String): Boolean =
            key in scalars || containers.any { key == it || key.startsWith("$it.") || key.startsWith("$it[") }
    }

    /** 생성자 매개변수를 따라 내려가며 kebab-case 잎 키를 모은다 (Map · Collection · 단순 타입이 잎). */
    private fun leafKeys(type: KClass<*>): List<String> =
        (type.primaryConstructor?.parameters ?: emptyList()).flatMap { parameter ->
            val name = kebab(requireNotNull(parameter.name))
            val kotlinType = parameter.type.jvmErasure
            if (kotlinType.isData && kotlinType.java.packageName.startsWith("dev.sumin.skeleton")) listOf(name) + leafKeys(kotlinType)
            else listOf(name)
        }

    private fun kebab(camel: String): String = camel.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
}
