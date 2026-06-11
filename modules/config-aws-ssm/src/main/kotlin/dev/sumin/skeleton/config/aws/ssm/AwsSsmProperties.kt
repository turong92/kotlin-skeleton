package dev.sumin.skeleton.config.aws.ssm

data class AwsSsmProperties(
    val enabled: Boolean = false,
    val region: String = "ap-northeast-2",
    val credentialProfile: String = "",
    val failFast: Boolean = true,
    val paths: List<String> = emptyList(),
)
