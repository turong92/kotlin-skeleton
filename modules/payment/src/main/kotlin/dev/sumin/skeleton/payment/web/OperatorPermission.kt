package dev.sumin.skeleton.payment.web

/**
 * 운영자 컨트롤러 핸들러가 요구하는 권한 — 핸들러마다 하나, 이 애너테이션이 권한 표다. 없는 핸들러는 인터셉터도 컨트롤러도 거절한다(403).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class OperatorPermission(val value: String)
