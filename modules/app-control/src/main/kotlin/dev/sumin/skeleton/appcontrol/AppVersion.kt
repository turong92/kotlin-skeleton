package dev.sumin.skeleton.appcontrol

/**
 * 앱 버전 `1`~`4` 칸의 숫자(`1.10.2`). 칸마다 숫자로 비교한다 - 글자로 비교하면 `1.10` 이 `1.9` 보다 앞선다. 모자란 칸은 0 (`1.2` = `1.2.0`).
 * 스토어 빌드 번호(`+12`) · 접미사(`-beta`)는 받지 않는다 - 최소 버전은 사용자에게 보이는 버전 이름으로 정한다.
 */
class AppVersion private constructor(private val parts: List<Int>, val text: String) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0

    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()

    override fun toString(): String = text

    companion object {
        private val SHAPE = Regex("^\\d{1,9}(\\.\\d{1,9}){0,3}$")

        /** 모양이 맞지 않으면 null */
        fun parse(raw: String): AppVersion? =
            if (SHAPE.matches(raw)) AppVersion(raw.split('.').map { it.toInt() }, raw) else null
    }
}
