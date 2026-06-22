package dev.sumin.skeleton.common.failure

/**
 * Runtime failure handling policy for reusable skeleton modules.
 *
 * - [THROW]: core use case failed. Re-throw so Spring transactions can roll back
 *   and the outer boundary can log/translate the exception once.
 * - [LOG_AND_CONTINUE]: optional side effect failed. Log and continue with a
 *   fallback value.
 * - [LOG_AND_SKIP]: optional unit of work failed. Log and skip the unit.
 */
enum class FailurePolicy {
    THROW,
    LOG_AND_CONTINUE,
    LOG_AND_SKIP,
}
