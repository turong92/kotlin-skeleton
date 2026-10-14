package dev.sumin.skeleton.app.nativecheck

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class NativeCheckApplication

fun main(args: Array<String>) {
    runApplication<NativeCheckApplication>(*args)
}
