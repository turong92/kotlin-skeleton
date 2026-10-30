package dev.sumin.skeleton.workspace

import java.text.Normalizer
import java.util.Locale

/** 화면 주소(`/w/<slug>/…`)에 쓰는 이름의 규칙 — 소문자 · 숫자 · 하이픈, 3~40자, 앞뒤 하이픈 없음 */
object WorkspaceSlugs {
    const val MAX = 40
    private const val FALLBACK = "team"
    val VALID = Regex("^[a-z0-9](?:[a-z0-9-]{1,38})[a-z0-9]$")

    /** 이름에서 자동으로 만든 slug — 악센트는 떼고, 라틴 글자 · 숫자가 모자라면(3자 미만) `team` */
    fun fromName(name: String): String {
        val ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        val slug = ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(MAX).trimEnd('-')
        return if (slug.length < 3) FALLBACK else slug
    }

    /** 겹칠 때의 후보 — `base`, `base-2`, `base-3`… (40자를 넘지 않게 base 를 줄인다) */
    fun candidate(base: String, attempt: Int): String {
        if (attempt <= 1) return base
        val suffix = "-$attempt"
        return base.take(MAX - suffix.length).trimEnd('-') + suffix
    }
}
