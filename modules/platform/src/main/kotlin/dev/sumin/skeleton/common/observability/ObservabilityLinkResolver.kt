package dev.sumin.skeleton.common.observability

fun interface ObservabilityLinkResolver {
    fun resolve(context: ObservabilityContext): List<ObservabilityLink>
}
