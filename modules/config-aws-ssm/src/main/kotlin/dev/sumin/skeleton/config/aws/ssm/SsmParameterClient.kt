package dev.sumin.skeleton.config.aws.ssm

fun interface SsmParameterClient {
    fun getParametersByPath(path: String): Map<String, String>
}

fun interface SsmParameterClientFactory {
    fun create(properties: AwsSsmProperties): SsmParameterClient
}
