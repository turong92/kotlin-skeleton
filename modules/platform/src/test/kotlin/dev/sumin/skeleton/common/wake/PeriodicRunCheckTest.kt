package dev.sumin.skeleton.common.wake

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class PeriodicRunCheckTest {
    @Test
    fun `fails startup when nothing would ever run the periodic work, and says what to add`() {
        val e = assertFailsWith<IllegalStateException> { PeriodicRunCheck.require("skeleton.inquiry.retention", MockEnvironment(), schedulerPresent = false) }
        assertTrue(e.message!!.contains("skeleton.inquiry.retention") && e.message!!.contains("scheduler") && e.message!!.contains("on-request"))
    }

    @Test
    fun `passes with the scheduler module or with the request-driven job queue mode`() {
        PeriodicRunCheck.require("x", MockEnvironment(), schedulerPresent = true)
        PeriodicRunCheck.require("x", MockEnvironment().withProperty("skeleton.job-queue.mode", "on-request"), schedulerPresent = false)
        PeriodicRunCheck.require("x", MockEnvironment().withProperty("skeleton.job-queue.mode", "ON_REQUEST"), schedulerPresent = false)
    }
}
