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
import java.util.concurrent.LinkedBlockingQueue
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

    private const val MAX_RETRIES = 3
    private val RETRYABLE_STATUS = setOf(429, 502, 503, 504)
    private const val POLL_MS = 150L

    /** Abort a stream that goes completely silent this long — it is wedged (a live LLM stream never
     *  pauses minutes between bytes). Bounds a stall to this instead of the 10-min request timeout, and
     *  keeps cancellation responsive (a blocking readLine can't see the indicator). */
    private const val SSE_IDLE_CAP_MS = 180_000L

    /** Parse an SSE event line. Returns null for comments, heartbeat, or blank lines. */
    data class SseEvent(val event: String, val data: String)

    /**
     * Send a POST request and read the response as an SSE (Server-Sent Events) stream.
     * Calls [onEvent] for each parsed event. Returns the full response body for non-streaming
     * fallback. Cancellation aborts the connection immediately.
     */
    fun postJsonSse(
        url: String,
        headers: Map<String, String>,
        body: String,
        indicator: ProgressIndicator,
        onEvent: (SseEvent) -> Unit,
    ): SseResult {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()

        var attempt = 0
        while (true) {
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
            val response = awaitCancellable(future, indicator)
            val status = response.statusCode()
            if (status in 200..299) return streamBody(status, response, indicator, onEvent)

            // Transient error BEFORE any stream data — safe to retry the connection (no partial output),
            // mirroring postJson. A 429/503 at connect no longer hard-aborts the turn.
            val errorBody = try { response.body()?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty() } catch (_: Exception) { "" }
            if (status in RETRYABLE_STATUS && attempt < MAX_RETRIES) {
                val waitMillis = retryAfterMillis(response) ?: (500L shl attempt)
                attempt++
                sleepCancellable(waitMillis, indicator)
                continue
            }
            return SseResult(status, errorBody, isError = true)
        }
    }

    /**
     * Read the 2xx response body as an SSE stream on a daemon thread while the caller polls a queue — so a
     * blocking readLine can't hide cancellation, and a stalled stream aborts at [SSE_IDLE_CAP_MS]. (The
     * old loop only checked the indicator BETWEEN lines, so a mid-line wedge ignored Stop until the 10-min
     * request timeout.) The future is already complete once headers arrive, so we close the reader — not
     * cancel the future — to unblock a wedged read.
     */
    private fun streamBody(
        status: Int,
        response: HttpResponse<java.io.InputStream>,
        indicator: ProgressIndicator,
        onEvent: (SseEvent) -> Unit,
    ): SseResult {
        val reader = response.body()?.bufferedReader(Charsets.UTF_8) ?: return SseResult(status, "", isError = false)
        val eof = Any()
        val queue = LinkedBlockingQueue<Any>()
        val readerError = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val readerThread = Thread {
            try {
                while (true) queue.put(reader.readLine() ?: break)
            } catch (e: Exception) {
                readerError.set(e) // happens-before the consumer via the queue's put/poll barrier
            } finally {
                queue.put(eof)
            }
        }.apply { isDaemon = true; name = "geai-sse-reader"; start() }

        var eventType = ""
        var dataBuffer = StringBuilder()
        var idleMs = 0L
        try {
            while (true) {
                if (indicator.isCanceled) return SseResult(0, "", isError = false, isCancelled = true)
                val item = queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                if (item == null) {
                    idleMs += POLL_MS
                    if (idleMs >= SSE_IDLE_CAP_MS) {
                        throw LlmException("Stream stalled — no data for ${SSE_IDLE_CAP_MS / 1000}s; aborted. Try again.")
                    }
                    continue
                }
                idleMs = 0
                if (item === eof) {
                    val err = readerError.get()
                    if (err != null && !indicator.isCanceled) throw LlmException("SSE stream error: ${err.message}", cause = err)
                    break
                }
                val line = item as String
                when {
                    line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val data = line.removePrefix("data:").trim()
                        if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                        dataBuffer.append(data)
                    }
                    line.isEmpty() && dataBuffer.isNotEmpty() -> {
                        onEvent(SseEvent(eventType.ifBlank { "message" }, dataBuffer.toString()))
                        eventType = ""
                        dataBuffer = StringBuilder()
                    }
                }
            }
        } finally {
            runCatching { reader.close() } // unblocks the daemon reader + releases the connection
        }
        if (dataBuffer.isNotEmpty()) {
            onEvent(SseEvent(eventType.ifBlank { "message" }, dataBuffer.toString()))
        }
        return SseResult(status, "", isError = false)
    }

    data class SseResult(val statusCode: Int, val errorBody: String, val isError: Boolean, val isCancelled: Boolean = false)

    fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        indicator: ProgressIndicator,
        requestTimeout: Duration = Duration.ofMinutes(5),
    ): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()

        var attempt = 0
        while (true) {
            val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            val response = awaitCancellable(future, indicator)
            val status = response.statusCode()
            val payload = response.body().orEmpty()
            if (status in 200..299) return payload

            // Transient errors are worth retrying with backoff; honour Retry-After when present.
            if (status in RETRYABLE_STATUS && attempt < MAX_RETRIES) {
                val waitMillis = retryAfterMillis(response) ?: (500L shl attempt)
                attempt++
                sleepCancellable(waitMillis, indicator)
                continue
            }
            throw LlmException(describeHttpError(status, payload), statusCode = status)
        }
    }

    private fun retryAfterMillis(response: HttpResponse<*>): Long? =
        response.headers().firstValue("retry-after").orElse(null)
            ?.trim()?.toLongOrNull()
            ?.let { (it * 1000L).coerceIn(0L, 60_000L) }

    private fun sleepCancellable(totalMillis: Long, indicator: ProgressIndicator) {
        var remaining = totalMillis
        while (remaining > 0) {
            if (indicator.isCanceled) throw ProcessCanceledException()
            val slice = minOf(remaining, 150L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            }
            remaining -= slice
        }
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
                // Still in flight — loop and re-check cancellation.
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
        return "HTTP $status$hint: ${JsonSupport.humanError(payload)}"
    }
}
