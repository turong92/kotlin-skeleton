package dev.sumin.skeleton.app.sample.cheer

import dev.sumin.skeleton.payment.PurchaseRequest
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class CheerCatalogTest {
    @Test
    fun `the name on the payment window does not depend on the JVM default locale`() {
        val saved = Locale.getDefault()
        try {
            listOf(Locale.GERMANY, Locale.forLanguageTag("hi-IN"), Locale.forLanguageTag("ar-EG"), Locale.KOREA).forEach { locale ->
                Locale.setDefault(locale)
                val quote = CheerCatalog().quote(PurchaseRequest("acc", CheerCatalog.ITEM_TYPE, "cheer-3000", 1, null))
                assertEquals("응원하기 3,000원", quote.orderName, "default locale $locale")
            }
        } finally {
            Locale.setDefault(saved)
        }
    }
}
