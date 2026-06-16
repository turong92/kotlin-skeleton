package dev.sumin.skeleton.common.logging

import kotlin.test.Test
import kotlin.test.assertEquals

class SkeletonLoggersTest {
    @Test
    fun `debug logger category names are stable`() {
        assertEquals("skeleton.debug.request", SkeletonLoggers.REQUEST)
        assertEquals("skeleton.debug.external-http", SkeletonLoggers.EXTERNAL_HTTP)
        assertEquals("skeleton.debug.auth", SkeletonLoggers.AUTH)
        assertEquals("skeleton.debug.payment", SkeletonLoggers.PAYMENT)
        assertEquals("skeleton.debug.sql", SkeletonLoggers.SQL)
        assertEquals("skeleton.debug.async", SkeletonLoggers.ASYNC)
    }

    @Test
    fun `debug logger factory returns named logger`() {
        assertEquals(SkeletonLoggers.REQUEST, SkeletonLoggers.logger(SkeletonLoggers.REQUEST).name)
        assertEquals(SkeletonLoggers.EXTERNAL_HTTP, SkeletonLoggers.externalHttp().name)
        assertEquals(SkeletonLoggers.ASYNC, SkeletonLoggers.async().name)
    }
}
