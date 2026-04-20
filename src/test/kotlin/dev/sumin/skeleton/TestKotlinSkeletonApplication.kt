package dev.sumin.skeleton

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<KotlinSkeletonApplication>().with(TestcontainersConfiguration::class).run(*args)
}
