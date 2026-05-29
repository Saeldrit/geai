package com.github.saeldrit.geai.llm.http

import com.github.saeldrit.geai.llm.LlmException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Minimal blocking JSON-over-HTTPS transport built on the JDK [HttpClient] (no extra deps).
 * Cancellation is cooperative: the in-flight request is aborted when [ProgressIndicator] is
 * cancelled, surfacing as [ProcessCanceledException] so the agent loop unwinds cleanly.
 */
internal object HttpTransport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        indicator: ProgressIndicator,
        requestTimeout: Duration = Duration.ofMinutes(5),
    ): String {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
        headers.forEach { (key, value) -> builder.header(key, value) }

        val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val response = awaitCancellable(future, indicator)

        val status = response.statusCode()
        val payload = response.body().orEmpty()
        if (status !in 200..299) {
            throw LlmException(describeHttpError(status, payload), statusCode = status)
        }
        return payload
    }

    private fun <T> awaitCancellable(future: CompletableFuture<T>, indicator: ProgressIndicator): T {
        while (true) {
            if (indicator.isCanceled) {
                future.cancel(true)
                throw ProcessCanceledException()
            }
            try {
                return future.get(150, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // request still in flight — loop and re-check cancellation
            } catch (_: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            } catch (e: ExecutionException) {
                val cause = e.cause ?: e
                throw LlmException("Network error: ${cause.message ?: cause.javaClass.simpleName}", cause = cause)
            }
        }
    }

    private fun describeHttpError(status: Int, payload: String): String {
        val hint = when (status) {
            401, 403 -> " (check the API key in Settings | Tools | Geai)"
            429 -> " (rate limited — slow down or check your quota)"
            in 500..599 -> " (provider server error — retry later)"
            else -> ""
        }
        return "HTTP $status$hint: ${payload.take(2000)}"
    }
}
