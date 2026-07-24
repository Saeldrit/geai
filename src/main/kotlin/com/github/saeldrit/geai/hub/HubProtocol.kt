package com.github.saeldrit.geai.hub

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed class WsMessage {


    @Serializable
    @SerialName("spoke_hello")
    data class SpokeHello(
        val spokeId: String,
        val ideType: String,
        val agentName: String,
        val capabilities: List<String> = emptyList(),
        val version: String = "",
        val projectName: String = "",
        val projectPath: String = ""
    ) : WsMessage()

    @Serializable
    @SerialName("spoke_metrics")
    data class SpokeMetrics(
        val spokeId: String,
        val processCpuPct: Double = -1.0,
        val systemCpuPct: Double = -1.0,
        val heapUsedMb: Long = 0,
        val heapMaxMb: Long = 0,
        val systemMemUsedMb: Long = 0,
        val systemMemTotalMb: Long = 0,
        val threads: Int = 0,
        val uptimeSec: Long = 0
    ) : WsMessage()

    @Serializable
    @SerialName("spoke_status")
    data class SpokeStatus(
        val spokeId: String,
        val agentName: String = "",
        val state: String,
        val currentTaskId: String? = null,
        val progressPercent: Int = 0,
        val statusMessage: String = ""
    ) : WsMessage()

    @Serializable
    @SerialName("spoke_event")
    data class SpokeEvent(
        val spokeId: String,
        val agentName: String = "",
        val eventType: String,
        val payload: String = ""
    ) : WsMessage()

    @Serializable
    @SerialName("agent_progress")
    data class AgentProgress(
        val spokeId: String = "",
        val taskId: String,
        val agentName: String = "",
        val percent: Int,
        val message: String = "",
        val artifacts: List<String> = emptyList()
    ) : WsMessage()

    @Serializable
    @SerialName("task_log")
    data class TaskLog(
        val spokeId: String = "",
        val taskId: String,
        val line: String
    ) : WsMessage()

    @Serializable
    @SerialName("task_result")
    data class TaskResult(
        val spokeId: String = "",
        val taskId: String,
        val agentName: String = "",
        val outcome: String,
        val artifacts: List<String> = emptyList(),
        val outputLog: String = "",
        val errorMessage: String = "",
        val inputTokens: Long = 0,
        val outputTokens: Long = 0
    ) : WsMessage()

    @Serializable
    @SerialName("contract_publish")
    data class ContractPublish(
        val spokeId: String = "",
        val contractId: String,
        val name: String,
        val version: String = "1.0.0",
        val content: String = "",
        val mimeType: String = "text/plain",
        /** Task that produced this contract — enables the rejection → rework loop. */
        val taskId: String = ""
    ) : WsMessage()

    @Serializable
    @SerialName("contract_reject")
    data class ContractReject(
        val spokeId: String = "",
        val taskId: String,
        val contractId: String = "",
        val name: String = "",
        val reason: String,
        val requestedChanges: List<String> = emptyList()
    ) : WsMessage()


    @Serializable
    @SerialName("hello_ack")
    data class HelloAck(
        val hubVersion: String = "",
        val message: String = ""
    ) : WsMessage()

    @Serializable
    @SerialName("task_assign")
    data class TaskAssign(
        val taskId: String,
        val taskName: String,
        val description: String = "",
        val role: String = "any",
        val dependsOn: List<String> = emptyList(),
        val requiredContracts: List<String> = emptyList(),
        val contracts: List<ContractPayload> = emptyList(),
        val upstream: List<UpstreamPayload> = emptyList(),
        val metadata: Map<String, String> = emptyMap(),
        val timeoutSeconds: Int = 600
    ) : WsMessage()

    @Serializable
    @SerialName("task_cancel")
    data class TaskCancel(
        val taskId: String,
        val reason: String = ""
    ) : WsMessage()

    /** Hub → Spoke: fetch the content of a file the agent produced (project-relative path). */
    @Serializable
    @SerialName("file_request")
    data class FileRequest(
        val requestId: String,
        val taskId: String = "",
        val path: String
    ) : WsMessage()

    /** Spoke → Hub: the requested file's bytes (base64), or an [error] describing why not. */
    @Serializable
    @SerialName("file_content")
    data class FileContent(
        val spokeId: String = "",
        val requestId: String,
        val path: String,
        val contentBase64: String = "",
        val mimeType: String = "application/octet-stream",
        val sizeBytes: Long = 0,
        val error: String = ""
    ) : WsMessage()


    @Serializable
    @SerialName("ping")
    data class Ping(val timestampMs: Long = 0) : WsMessage()

    @Serializable
    @SerialName("pong")
    data class Pong(val timestampMs: Long = 0) : WsMessage()

    companion object {
        /** The single Json configuration both sides must use. */
        val wire: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            classDiscriminator = "type"
        }

        fun encode(message: WsMessage): String = wire.encodeToString(serializer(), message)

        fun decode(raw: String): WsMessage = wire.decodeFromString(serializer(), raw)
    }
}

@Serializable
data class ContractPayload(
    val contractId: String,
    val name: String,
    val version: String = "",
    val content: String = "",
    val mimeType: String = "text/plain"
)

@Serializable
data class UpstreamPayload(
    val taskId: String,
    val name: String,
    val summary: String = "",
    val artifacts: List<String> = emptyList()
)
