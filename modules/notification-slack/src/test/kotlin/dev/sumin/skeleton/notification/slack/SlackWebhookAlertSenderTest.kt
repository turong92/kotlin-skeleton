package dev.sumin.skeleton.notification.slack

import dev.sumin.skeleton.common.http.ExternalHttpClient
import dev.sumin.skeleton.common.http.ExternalHttpRequestSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import reactor.core.publisher.Mono

class SlackWebhookAlertSenderTest {
    @Test
    fun `does not send when Slack is disabled`() {
        val httpClient = RecordingExternalHttpClient()
        val sender = SlackWebhookAlertSender(
            httpClient = httpClient,
            properties = SlackNotificationProperties(enabled = false, webhookUrl = "https://hooks.slack.example/default"),
            messageFactory = SlackAlertMessageFactory(SlackNotificationProperties()),
        )

        sender.send(SlackAlert(title = "Ignored", message = "disabled"))

        assertTrue(httpClient.posts.isEmpty())
    }

    @Test
    fun `does not send below configured severity`() {
        val httpClient = RecordingExternalHttpClient()
        val properties = SlackNotificationProperties(
            enabled = true,
            webhookUrl = "https://hooks.slack.example/default",
            minimumSeverity = SlackAlertSeverity.ERROR,
        )
        val sender = SlackWebhookAlertSender(
            httpClient = httpClient,
            properties = properties,
            messageFactory = SlackAlertMessageFactory(properties),
        )

        sender.send(SlackAlert(title = "Too low", message = "warning", severity = SlackAlertSeverity.WARNING))

        assertTrue(httpClient.posts.isEmpty())
    }

    @Test
    fun `posts payload to route webhook`() {
        val httpClient = RecordingExternalHttpClient()
        val properties = SlackNotificationProperties(
            enabled = true,
            routes = mapOf(
                "payment" to SlackNotificationProperties.Route(
                    webhookUrl = "https://hooks.slack.example/payment",
                ),
            ),
        )
        val sender = SlackWebhookAlertSender(
            httpClient = httpClient,
            properties = properties,
            messageFactory = SlackAlertMessageFactory(properties),
        )

        sender.send(
            SlackAlert(
                title = "Payment failed",
                message = "approve failed",
                severity = SlackAlertSeverity.ERROR,
                route = "payment",
            ),
        )

        val post = httpClient.posts.single()
        assertEquals("slack", post.clientName)
        assertEquals("https://hooks.slack.example/payment", post.path)
        assertEquals("[ERROR] Payment failed", (post.body as SlackWebhookPayload).text)
    }

    @Test
    fun `retries configured delivery failures`() {
        val httpClient = FailingOnceExternalHttpClient()
        val properties = SlackNotificationProperties(
            enabled = true,
            webhookUrl = "https://hooks.slack.example/default",
            retryAttempts = 1,
        )
        val sender = SlackWebhookAlertSender(
            httpClient = httpClient,
            properties = properties,
            messageFactory = SlackAlertMessageFactory(properties),
        )

        sender.send(SlackAlert(title = "Retry", message = "once", severity = SlackAlertSeverity.ERROR))

        assertEquals(2, httpClient.attempts)
    }

    private class RecordingExternalHttpClient : ExternalHttpClient {
        val posts = mutableListOf<RecordedPost>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> {
            ExternalHttpRequestSpec().apply(customize)
            posts += RecordedPost(clientName, path, body)
            return Mono.just("ok" as T)
        }

        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")
    }

    private class FailingOnceExternalHttpClient : ExternalHttpClient {
        var attempts = 0

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> post(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> =
            Mono.defer {
                attempts += 1
                if (attempts == 1) {
                    Mono.error(IllegalStateException("temporary"))
                } else {
                    Mono.just("ok" as T)
                }
            }

        override fun <T : Any> get(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> postForm(
            clientName: String,
            path: String,
            form: Map<String, String>,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> put(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> patch(
            clientName: String,
            path: String,
            body: Any?,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")

        override fun <T : Any> delete(
            clientName: String,
            path: String,
            responseType: Class<T>,
            customize: ExternalHttpRequestSpec.() -> Unit,
        ): Mono<T> = error("not used")
    }

    private data class RecordedPost(
        val clientName: String,
        val path: String,
        val body: Any?,
    )
}
