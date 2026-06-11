package dev.sumin.skeleton.redis.lock

enum class LockFailurePolicy {
    THROW,
    SKIP,
    PROCEED_ON_BACKEND_FAILURE,
}
