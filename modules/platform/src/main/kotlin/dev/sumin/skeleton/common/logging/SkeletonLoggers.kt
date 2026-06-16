package dev.sumin.skeleton.common.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Stable logger categories for runtime debugging.
 *
 * Class loggers are still useful for normal implementation logs. Use these
 * categories when an operator should be able to enable a whole concern without
 * knowing concrete class names.
 */
object SkeletonLoggers {
    const val REQUEST = "skeleton.debug.request"
    const val EXTERNAL_HTTP = "skeleton.debug.external-http"
    const val AUTH = "skeleton.debug.auth"
    const val PAYMENT = "skeleton.debug.payment"
    const val SQL = "skeleton.debug.sql"

    fun logger(name: String): Logger = LoggerFactory.getLogger(name)

    fun request(): Logger = logger(REQUEST)

    fun externalHttp(): Logger = logger(EXTERNAL_HTTP)

    fun auth(): Logger = logger(AUTH)

    fun payment(): Logger = logger(PAYMENT)

    fun sql(): Logger = logger(SQL)
}
