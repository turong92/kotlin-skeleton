package dev.sumin.skeleton.point.web

import dev.sumin.skeleton.common.FieldValidationException
import dev.sumin.skeleton.point.PointCursor
import java.time.Instant
import java.util.Base64

/** 내역 커서의 글자 모양 — 불투명한 글자 하나(`<시각 마이크로초>.<id>` 를 URL 안전 base64 로). 읽을 수 없으면 `400 VALIDATION_FAILED`(field `cursor`) */
internal object PointCursors {
    fun encode(cursor: PointCursor): String {
        val micros = cursor.createdAt.epochSecond * 1_000_000 + cursor.createdAt.nano / 1_000
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$micros.${cursor.id}".toByteArray(Charsets.UTF_8))
    }

    fun decode(text: String?): PointCursor? {
        if (text.isNullOrBlank()) return null
        val raw = try {
            String(Base64.getUrlDecoder().decode(text), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw invalid()
        }
        val parts = raw.split('.', limit = 2)
        if (parts.size != 2 || !ID_SHAPE.matches(parts[1]) || parts[1].length > 40) throw invalid()
        val micros = parts[0].toLongOrNull()?.takeIf { it in 0..MAX_MICROS } ?: throw invalid()
        return PointCursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L), Math.floorMod(micros, 1_000_000L) * 1_000), parts[1])
    }

    /** 두 DB 가 저장하는 시각의 범위 안(1970 ~ 9999년) — 밖의 값은 질의에서 오류가 되어 500 이 되므로 여기서 400 으로 끊는다 */
    private const val MAX_MICROS = 253_402_300_799L * 1_000_000L
    private val ID_SHAPE = Regex("[A-Za-z0-9_-]+")

    private fun invalid() = FieldValidationException("cursor", "Pattern", "cursor is not one this list gave out")
}
