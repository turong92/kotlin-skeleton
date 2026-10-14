package dev.sumin.skeleton.legal

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * 네이티브 이미지에 약관 문서를 넣는다 (docs/native-image.md): 모듈의 TEMPLATE(`legal-templates/`)와 앱이 두는 기본 위치(`legal/`, `skeleton.legal.location`).
 * 위치를 `classpath:` 밖(`file:`)으로 바꾼 앱은 영향이 없다. 위치를 다른 classpath 폴더로 바꾼 앱은 자기 `RuntimeHintsRegistrar` 로 그 폴더를 더한다.
 * `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다.
 */
class LegalNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf("legal-templates", "legal").forEach { base ->
            hints.resources().registerPattern("$base/*")
            hints.resources().registerPattern("$base/*/*")
        }
    }
}
