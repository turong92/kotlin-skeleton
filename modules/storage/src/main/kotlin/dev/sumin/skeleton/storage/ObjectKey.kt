package dev.sumin.skeleton.storage

data class ObjectKey(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "Object key must not be blank." }
        require(value == value.trim()) { "Object key must not have leading or trailing whitespace." }
        require(!value.startsWith("/")) { "Object key must be relative, not absolute." }
        require(value.none { it.isISOControl() || it == '\\' }) {
            "Object key must not contain control characters or backslashes."
        }
        require(value.split('/').none { it == "." || it == ".." }) {
            "Object key must not contain path traversal segments."
        }
    }

    override fun toString(): String = value
}
