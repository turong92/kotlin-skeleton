package dev.sumin.skeleton.app.workbench

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<WorkbenchApplication>().with(TestcontainersConfiguration::class).run(*args)
}
