package dev.sumin.skeleton.app.workbench

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class WorkbenchApplication

fun main(args: Array<String>) {
	runApplication<WorkbenchApplication>(*args)
}
