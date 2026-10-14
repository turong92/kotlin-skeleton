package dev.sumin.skeleton.time

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/** 네이티브 이미지에 나라별 시간대 표(`country-zones.tsv`)를 넣는다 (docs/native-image.md). `META-INF/spring/aot.factories` 로 등록 — JVM 에서는 아무 일도 하지 않는다. */
class TimeNativeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.resources().registerPattern("dev/sumin/skeleton/time/country-zones.tsv")
    }
}
