package dev.sumin.skeleton.api

import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
class SkeletonAsyncFailureProbe {
    @Async("skeletonAsyncTaskExecutor")
    fun fail() {
        throw IllegalStateException("skeleton async failure probe")
    }
}
