package dev.sumin.skeleton.common.capabilities

import tools.jackson.databind.json.JsonMapper

/** capabilities.json 을 읽고 쓰는 최소 도구 — 순서를 지키는 Map · List 로 다룬다(키 순서가 곧 사람이 읽는 순서). */
internal object Json {
    private val mapper = JsonMapper.builder().build()

    fun parse(text: String): Any? = mapper.readValue(text, Any::class.java)

    fun pretty(value: Any?): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)

    /** 시험에서 카탈로그를 망가뜨려 보기 위한 깊은 복사. */
    fun copy(value: Any?): Any? = parse(pretty(value))
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(): List<Any?> = this as List<Any?>

internal fun Any?.strings(): List<String> = (this as? List<*>)?.map { it as String }.orEmpty()

internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
