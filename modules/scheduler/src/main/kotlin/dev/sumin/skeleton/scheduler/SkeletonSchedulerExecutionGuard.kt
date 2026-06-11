package dev.sumin.skeleton.scheduler

import org.springframework.core.env.Environment

class SkeletonSchedulerExecutionGuard(
    private val environment: Environment,
    private val properties: SkeletonSchedulerProperties,
) {
    fun canExecute(annotation: SkeletonScheduled): Boolean {
        if (!properties.enabled || !properties.localExecution.enabled) return false
        if (!profilesAllowed(properties.localExecution.allowedProfiles)) return false
        if (profilesBlocked(properties.localExecution.blockedProfiles)) return false
        if (!profilesAllowed(annotation.profiles.toSet())) return false
        if (!enabledByProperty(annotation.enabledProperty)) return false
        return true
    }

    private fun profilesAllowed(requiredProfiles: Set<String>): Boolean =
        requiredProfiles.isEmpty() || activeProfiles().any { it in requiredProfiles }

    private fun profilesBlocked(blockedProfiles: Set<String>): Boolean =
        blockedProfiles.isNotEmpty() && activeProfiles().any { it in blockedProfiles }

    private fun activeProfiles(): Set<String> =
        environment.activeProfiles.toSet().ifEmpty { setOf("default") }

    private fun enabledByProperty(propertyName: String): Boolean =
        propertyName.isBlank() || environment.getProperty(propertyName, Boolean::class.java, true)
}
