package dev.sumin.skeleton.config.aws.ssm

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParametersByPathRequest

class AwsSdkSsmParameterClientFactory : SsmParameterClientFactory {
    override fun create(properties: AwsSsmProperties): SsmParameterClient {
        val builder = SsmClient.builder()
            .region(Region.of(properties.region))

        if (properties.credentialProfile.isNotBlank()) {
            builder.credentialsProvider(ProfileCredentialsProvider.create(properties.credentialProfile))
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.builder().build())
        }

        return AwsSdkSsmParameterClient(builder.build())
    }
}

class AwsSdkSsmParameterClient(
    private val client: SsmClient,
) : SsmParameterClient {
    override fun getParametersByPath(path: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var nextToken: String? = null
        do {
            val request = GetParametersByPathRequest.builder()
                .path(path)
                .recursive(true)
                .withDecryption(true)
                .nextToken(nextToken)
                .build()
            val response = client.getParametersByPath(request)
            response.parameters().forEach { parameter ->
                values[parameter.name()] = parameter.value()
            }
            nextToken = response.nextToken()
        } while (!nextToken.isNullOrBlank())
        return values
    }
}
