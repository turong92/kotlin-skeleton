package dev.sumin.skeleton

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KotlinSkeletonApplication

fun main(args: Array<String>) {
	runApplication<KotlinSkeletonApplication>(*args)
}
