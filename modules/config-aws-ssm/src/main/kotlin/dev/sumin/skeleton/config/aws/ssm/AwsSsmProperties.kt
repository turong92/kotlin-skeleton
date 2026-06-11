package dev.sumin.skeleton.config.aws.ssm

data class AwsSsmProperties(
    val enabled: Boolean? = null,
    val region: String = "ap-northeast-2",
    val credentialProfile: String = "",
    val failFast: Boolean = true,
    val paths: List<String> = emptyList(),
)
