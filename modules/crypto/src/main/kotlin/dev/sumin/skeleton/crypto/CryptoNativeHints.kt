package dev.sumin.skeleton.crypto

import org.springframework.aot.hint.BindingReflectionHintsRegistrar
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/** 네이티브 이미지: 불투명 URL 토큰의 본문(JSON)을 컨트롤러 밖에서 읽고 쓴다 (docs/native-image.md). `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다. */
class CryptoNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        BindingReflectionHintsRegistrar().registerReflectionHints(hints.reflection(), OpaqueUrlTokenPayload::class.java)
    }
}
