package dev.sumin.skeleton.storage.image.jdbc

import dev.sumin.skeleton.storage.image.StoredVariant
import dev.sumin.skeleton.storage.image.jdbc.JdbcImageStatusStore.Companion.toJson
import dev.sumin.skeleton.storage.image.jdbc.JdbcImageStatusStore.Companion.variantsOf
import kotlin.test.Test
import org.assertj.core.api.Assertions.assertThat

class ImageVariantsJsonTest {
    @Test
    fun `other formats of a variant survive the status column and rows without them still read`() {
        val variants = mapOf(
            "thumb" to StoredVariant(400, 300, "jpg", listOf("webp")),
            "display" to StoredVariant(1600, 1200, "png"),
        )

        assertThat(variantsOf(variants.toJson())).isEqualTo(variants)
        // 이 변경 전에 적힌 줄
        assertThat(variantsOf("""{"thumb":{"w":400,"h":300,"ext":"jpg"}}""")).isEqualTo(mapOf("thumb" to StoredVariant(400, 300, "jpg")))
        assertThat(variants.toJson()!!.length).isLessThan(400)   // 열 길이 varchar(400) 안
    }

    @Test
    fun `a converted display extension with a digit (mp4) survives the status column`() {
        val variants = mapOf("thumb" to StoredVariant(400, 300, "jpg"), "display" to StoredVariant(400, 300, "mp4"))

        assertThat(variantsOf(variants.toJson())).isEqualTo(variants)
    }
}
