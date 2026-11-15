package dev.sumin.skeleton.appcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 버전은 글자가 아니라 숫자 칸으로 비교한다 - `1.10` 은 `1.9` 보다 크다 */
class AppVersionTest {
    private fun v(raw: String) = AppVersion.parse(raw)!!

    @Test
    fun `1_10 is newer than 1_9 although the text sorts the other way`() {
        assertTrue(v("1.10") > v("1.9"))
        assertTrue(v("1.10.0") > v("1.9.12"))
        assertTrue("1.10" < "1.9")
    }

    @Test
    fun `missing trailing parts count as zero`() {
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
        assertEquals(0, v("2").compareTo(v("2.0.0.0")))
        assertTrue(v("1.2.1") > v("1.2"))
    }

    @Test
    fun `only dotted numbers of one to four parts parse`() {
        assertNotNull(AppVersion.parse("1.2.3.4"))
        listOf("", "1.", ".1", "1..2", "v1.2", "1.2-beta", "1.2.3.4.5", "a.b", " 1.2", "1.2 ", "99999999999").forEach { assertNull(AppVersion.parse(it), it) }
    }
}
