package com.github.saeldrit.geai.hub

import com.intellij.openapi.diagnostic.logger
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class HubState {
    data object Disconnected : HubState()
    data class Connecting(val url: String) : HubState()
    data class Connected(val hubVersion: String) : HubState()
    data class Error(val message: String) : HubState()
}

/**
 * WebSocket transport to the geai Hub. Pure connection concern: connect, auto-reconnect with
 * exponential backoff, heartbeat, ordered sends, frame reassembly. Task execution lives in
 * [HubService].
 */
class HubClient(
    private val hubUrl: String,
    private val onState: (HubState) -> Unit,
    private val onOpen: () -> Unit,
    private val onMessage: (WsMessage) -> Unit
) {
    private val log = logger<HubClient>()
    private val scheduler = AppExecutorUtil.getAppScheduledExecutorService()

    private val desired = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    private var heartbeatFuture: ScheduledFuture<*>? = null

    @Volatile
    private var reconnectFuture: ScheduledFuture<*>? = null

    private val sendLock = Any()
    private var sendChain: CompletableFuture<Any?> = CompletableFuture.completedFuture<Any?>(null)

    val isConnected: Boolean get() = ws != null

    fun start() {
        if (!desired.compareAndSet(false, true)) return
        attempt.set(0)
        connectAttempt()
    }

    fun stop() {
        desired.set(false)
        reconnectFuture?.cancel(false)
        heartbeatFuture?.cancel(false)
        try {
            ws?.sendClose(WebSocket.NORMAL_CLOSURE, "Client disconnecting")
        } catch (_: Exception) {
        }
        ws = null
        onState(HubState.Disconnected)
    }

    fun send(message: WsMessage) {
        val text = try {
            WsMessage.encode(message)
        } catch (e: Exception) {
            log.warn("Failed to encode hub message", e)
            return
        }
        synchronized(sendLock) {
            sendChain = sendChain
                .exceptionally { null as Any? }
                .thenCompose<Any?> {
                    val socket = ws ?: return@thenCompose CompletableFuture.completedFuture<Any?>(null)
                    socket.sendText(text, true).thenApply<Any?> { null }
                }
                .whenComplete { _, err -> if (err != null) log.debug("Hub send failed: ${err.message}") }
        }
    }


    private fun connectAttempt() {
        if (!desired.get()) return
        onState(HubState.Connecting(hubUrl))
        try {
            HttpClient.newBuilder().build()
                .newWebSocketBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(8))
                .buildAsync(URI(hubUrl), FrameListener())
                .whenComplete { socket, err ->
                    when {
                        err != null -> {
                            if (desired.get()) {
                                onState(HubState.Error("Cannot reach hub at $hubUrl: ${rootMessage(err)}"))
                                scheduleReconnect()
                            }
                        }
                        !desired.get() -> {
                            try {
                                socket.abort()
                            } catch (_: Exception) {
                            }
                        }
                        else -> ws = socket
                    }
                }
        } catch (e: Exception) {
            onState(HubState.Error("Invalid hub URL '$hubUrl': ${e.message}"))
            desired.set(false)
        }
    }

    private fun scheduleReconnect() {
        if (!desired.get()) return
        val n = attempt.incrementAndGet()
        val delaySec = (1L shl minOf(n, 5)).coerceAtMost(30L)
        reconnectFuture?.cancel(false)
        reconnectFuture = scheduler.schedule({ connectAttempt() }, delaySec, TimeUnit.SECONDS)
    }

    private fun startHeartbeat() {
        heartbeatFuture?.cancel(false)
        heartbeatFuture = scheduler.scheduleWithFixedDelay(
            { if (isConnected) send(WsMessage.Ping(System.currentTimeMillis())) },
            25, 25, TimeUnit.SECONDS
        )
    }

    private fun handleClosed(reason: String) {
        ws = null
        heartbeatFuture?.cancel(false)
        if (desired.get()) {
            onState(HubState.Error(reason.ifBlank { "Connection lost" }))
            scheduleReconnect()
        } else {
            onState(HubState.Disconnected)
        }
    }

    private fun rootMessage(t: Throwable): String {
        var cur: Throwable = t
        while (true) {
            val cause = cur.cause
            if (cause == null || cause === cur) break
            cur = cause
        }
        return cur.message ?: cur.javaClass.simpleName
    }


    private inner class FrameListener : WebSocket.Listener {
        private val buffer = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            if (!desired.get()) {
                try {
                    webSocket.abort()
                } catch (_: Exception) {
                }
                return
            }
            ws = webSocket
            attempt.set(0)
            startHeartbeat()
            webSocket.request(1)
            this@HubClient.onOpen()
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletableFuture<*>? {
            buffer.append(data)
            if (last) {
                val raw = buffer.toString()
                buffer.setLength(0)
                try {
                    onMessage(WsMessage.decode(raw))
                } catch (e: Exception) {
                    log.warn("Unparseable hub message: ${e.message}. Raw: ${raw.take(200)}")
                }
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletableFuture<*>? {
            handleClosed("Hub closed connection ($statusCode${if (reason.isBlank()) "" else ": $reason"})")
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            handleClosed("Connection error: ${rootMessage(error)}")
        }
    }
}
