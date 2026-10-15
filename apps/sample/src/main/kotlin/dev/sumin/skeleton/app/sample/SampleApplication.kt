package dev.sumin.skeleton.app.sample

import dev.sumin.skeleton.app.sample.notes.SampleNativeHints
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ImportRuntimeHints

// 루트 패키지의 하위(app.sample)에 둔다: component scan 은 이 패키지 아래 앱 코드만 훑고, 모듈은 AutoConfiguration 으로만 붙는다.
@SpringBootApplication
@ImportRuntimeHints(SampleNativeHints::class)
class SampleApplication

fun main(args: Array<String>) {
    runApplication<SampleApplication>(*args)
}
