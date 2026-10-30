package dev.sumin.skeleton.auth.mfa

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrSvgTest {
    @Test
    fun `the matrix comes back as one path string made only of path commands`() {
        val qr = QrSvg.of("otpauth://totp/Notes:user%40example.com?secret=GEZDGNBVGY3TQOJQ&issuer=Notes")
        assertTrue(qr.path.startsWith("M"))
        assertTrue(qr.path.all { it in "MhvzZ0123456789 ,-" }, "no markup or script can ride in the path: ${qr.path.take(80)}")
        assertEquals(0, (qr.size - 8 - 17) % 4, "size = modules + a 4-module quiet zone on each side (modules = 17 + 4 * version)")
        assertTrue(qr.size in 29..400)
    }

    @Test
    fun `the same text gives the same drawing and another text another`() {
        assertEquals(QrSvg.of("a"), QrSvg.of("a"))
        assertTrue(QrSvg.of("a").path != QrSvg.of("b").path)
    }
}
