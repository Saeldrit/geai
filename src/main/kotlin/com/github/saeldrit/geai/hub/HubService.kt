package com.github.saeldrit.geai.hub

import com.github.saeldrit.geai.agent.AgentEvent
import com.github.saeldrit.geai.agent.AgentListener
import com.github.saeldrit.geai.agent.GeaiAgentService
import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.settings.effectiveHubUrl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class HubService(private val project: Project) : Disposable {

    companion object {
        fun getInstance(project: Project): HubService = project.getService(HubService::class.java)
        private const val VERSION = "1.2.0"
        /** Upper bound on a single file the hub may pull back — keeps a base64 frame sane. */
        private const val MAX_FILE_BYTES = 25L * 1024 * 1024
    }

    private val log = logger<HubService>()
    private val stateRef = AtomicReference<HubState>(HubState.Disconnected)
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var client: HubClient? = null

    @Volatile
    private var currentTask: WsMessage.TaskAssign? = null

    @Volatile
    private var cancelledTaskId: String? = null

    var onStateChanged: ((HubState) -> Unit)? = null

    val state: HubState get() = stateRef.get()
    val isConnected: Boolean get() = state is HubState.Connected

    private val ideType: String by lazy {
        val ide = ApplicationInfo.getInstance().fullApplicationName.lowercase()
        when {
            "webstorm" in ide -> "webstorm"
            "android studio" in ide -> "android-studio"
            "pycharm" in ide -> "pycharm"
            "goland" in ide -> "goland"
            "rider" in ide -> "rider"
            "clion" in ide -> "clion"
            "phpstorm" in ide -> "phpstorm"
            "rubymine" in ide -> "rubymine"
            else -> "intellij"
        }
    }

    private val spokeId: String by lazy {
        val basis = "${ideType}:${project.basePath ?: project.name}"
        val hash = Integer.toHexString(basis.hashCode()).takeLast(8)
        "geai-$ideType-${sanitize(project.name)}-$hash"
    }

    private val agentName: String by lazy { "${project.name} (${ideType})" }

    /** The REAL installed plugin version (e.g. "0.0.74") — what users see in the hub's Agents tab. */
    private val pluginVersion: String by lazy {
        runCatching {
            com.intellij.ide.plugins.PluginManagerCore
                .getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.github.saeldrit.geai"))
                ?.version
        }.getOrNull() ?: VERSION
    }

    private fun sanitize(s: String) = s.lowercase().replace(Regex("[^a-z0-9-]"), "-").take(24)


    @Synchronized
    fun connect() {
        if (client != null) return
        val url = GeaiSettings.getInstance().state.effectiveHubUrl()
        val newClient = HubClient(
            hubUrl = url,
            onState = { newState -> transition(newState) },
            onOpen = { sendHello() },
            onMessage = { message -> handleMessage(message) }
        )
        client = newClient
        newClient.start()
    }

    @Synchronized
    fun disconnect() {
        stopMetricsLoop()
        client?.stop()
        client = null
        currentTask = null
        transition(HubState.Disconnected)
    }

    fun toggle() {
        if (client == null) connect() else disconnect()
    }

    override fun dispose() {
        disconnect()
    }


    private var lastNotifiedConnected = false

    private fun transition(newState: HubState) {
        val old = stateRef.getAndSet(newState)
        onStateChanged?.invoke(newState)
        when (newState) {
            is HubState.Connected -> {
                if (!lastNotifiedConnected) {
                    lastNotifiedConnected = true
                    notify(
                        "Connected to geai Hub",
                        "Registered as \"$agentName\". Tasks from the hub will run through the geai agent.",
                        NotificationType.INFORMATION
                    )
                }
            }
            is HubState.Disconnected -> {
                if (lastNotifiedConnected) {
                    lastNotifiedConnected = false
                    notify("Disconnected from geai Hub", "", NotificationType.INFORMATION)
                }
            }
            is HubState.Error -> {
                if (old !is HubState.Error) {
                    notify("geai Hub connection problem", newState.message + " Reconnecting…", NotificationType.WARNING)
                }
                lastNotifiedConnected = false
            }
            is HubState.Connecting -> Unit
        }
    }

    private fun sendHello() {
        client?.send(
            WsMessage.SpokeHello(
                spokeId = spokeId,
                ideType = ideType,
                agentName = agentName,
                // Self-describing capabilities — the hub gates features on these, never on versions.
                capabilities = listOf("code-generation", ideType, roleCapability(), "plan", "verify", "map"),
                version = pluginVersion,
                projectName = project.name,
                projectPath = project.basePath ?: ""
            )
        )
        val task = currentTask
        client?.send(
            WsMessage.SpokeStatus(
                spokeId = spokeId,
                agentName = agentName,
                state = if (task != null) "busy" else "ready",
                currentTaskId = task?.taskId
            )
        )
        startMetricsLoop()
    }

    private fun roleCapability(): String = when (ideType) {
        "webstorm" -> "frontend"
        "android-studio" -> "android"
        else -> "backend"
    }

    private fun handleMessage(message: WsMessage) {
        when (message) {
            is WsMessage.HelloAck -> transition(HubState.Connected(message.hubVersion))
            is WsMessage.TaskAssign -> onTaskAssigned(message)
            is WsMessage.TaskCancel -> onTaskCancelled(message)
            is WsMessage.FileRequest -> onFileRequest(message)
            is WsMessage.Ping -> client?.send(WsMessage.Pong(System.currentTimeMillis()))
            is WsMessage.Pong -> Unit
            is WsMessage.SpokeHello, is WsMessage.SpokeStatus, is WsMessage.SpokeEvent,
            is WsMessage.SpokeMetrics, is WsMessage.AgentProgress, is WsMessage.TaskLog,
            is WsMessage.TaskResult, is WsMessage.FileContent,
            is WsMessage.ContractPublish, is WsMessage.ContractReject -> Unit
        }
    }

    /** Serves a produced file back to the hub. Reads on a pooled thread; refuses anything outside
     *  the project or larger than [MAX_FILE_BYTES]. Path is the same project-relative string the
     *  agent used in write_file, so it maps directly to an artifact shown in the hub. */
    private fun onFileRequest(req: WsMessage.FileRequest) {
        val activeClient = client ?: return
        com.intellij.util.concurrency.AppExecutorUtil.getAppExecutorService().execute {
            val reply = runCatching {
                val base = project.basePath ?: error("no project base path")
                val baseDir = File(base).canonicalFile
                val raw = req.path.trim().replace('\\', '/')
                val target = (if (File(raw).isAbsolute) File(raw) else File(baseDir, raw)).canonicalFile
                require(target.path == baseDir.path || target.path.startsWith(baseDir.path + File.separator)) {
                    "path is outside the project"
                }
                require(target.isFile) { "not a file: ${req.path}" }
                require(target.length() <= MAX_FILE_BYTES) {
                    "file too large (${target.length()} bytes; cap $MAX_FILE_BYTES)"
                }
                val bytes = target.readBytes()
                WsMessage.FileContent(
                    spokeId = spokeId,
                    requestId = req.requestId,
                    path = req.path,
                    contentBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                    mimeType = mimeOf(target.name),
                    sizeBytes = target.length()
                )
            }.getOrElse {
                WsMessage.FileContent(
                    spokeId = spokeId, requestId = req.requestId, path = req.path,
                    error = it.message ?: "read failed"
                )
            }
            activeClient.send(reply)
        }
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "json" -> "application/json"
        "md", "txt", "kt", "java", "yaml", "yml", "xml", "css", "js", "ts" -> "text/plain"
        "png" -> "image/png"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }


    @Volatile
    private var metricsFuture: java.util.concurrent.ScheduledFuture<*>? = null

    private fun startMetricsLoop() {
        metricsFuture?.cancel(false)
        metricsFuture = com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ sendMetrics() }, 3, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun stopMetricsLoop() {
        metricsFuture?.cancel(false)
        metricsFuture = null
    }

    private fun sendMetrics() {
        val activeClient = client ?: return
        runCatching {
            val osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                as? com.sun.management.OperatingSystemMXBean ?: return
            val heap = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage
            val mb = 1024L * 1024L
            activeClient.send(
                WsMessage.SpokeMetrics(
                    spokeId = spokeId,
                    processCpuPct = (osBean.processCpuLoad * 100).takeIf { it >= 0 } ?: -1.0,
                    systemCpuPct = (osBean.cpuLoad * 100).takeIf { it >= 0 } ?: -1.0,
                    heapUsedMb = heap.used / mb,
                    heapMaxMb = (heap.max.takeIf { it > 0 } ?: heap.committed) / mb,
                    systemMemUsedMb = (osBean.totalMemorySize - osBean.freeMemorySize) / mb,
                    systemMemTotalMb = osBean.totalMemorySize / mb,
                    threads = java.lang.management.ManagementFactory.getThreadMXBean().threadCount,
                    uptimeSec = java.lang.management.ManagementFactory.getRuntimeMXBean().uptime / 1000
                )
            )
        }
    }


    private fun kindOf(task: WsMessage.TaskAssign): String = task.metadata["kind"] ?: "task"
    private fun isSynthetic(task: WsMessage.TaskAssign): Boolean = kindOf(task) != "task"

    private fun onTaskAssigned(task: WsMessage.TaskAssign) {
        val agentService = GeaiAgentService.getInstance(project)
        if (currentTask != null || agentService.isRunning()) {
            client?.send(
                WsMessage.TaskResult(
                    spokeId = spokeId,
                    taskId = task.taskId,
                    agentName = agentName,
                    outcome = "requeue",
                    errorMessage = "Agent is busy with another task"
                )
            )
            return
        }
        currentTask = task
        cancelledTaskId = null
        sendStatus("busy", task.taskId, 0, "Starting: ${task.taskName}")
        sendLog(task.taskId, "── ${kindOf(task).uppercase()} ${task.taskId} \"${task.taskName}\" accepted by $agentName ──")
        if (!isSynthetic(task)) {
            notify("Hub task started", "${task.taskId}: ${task.taskName}", NotificationType.INFORMATION)
        }

        val listener = HubTaskListener(task)
        val prompt = when (kindOf(task)) {
            "plan" -> buildPlanPrompt(task)
            "verify" -> buildVerifyPrompt(task)
            "map" -> buildMapPrompt()
            else -> buildTaskPrompt(task)
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (cancelledTaskId == task.taskId) {
                finishTask(task, "cancelled", errorMessage = "Cancelled before start")
                return@invokeLater
            }
            try {
                agentService.newSession()
                agentService.submit(prompt, listener)
            } catch (e: Exception) {
                log.warn("Failed to submit hub task", e)
                finishTask(task, "failure", errorMessage = "Failed to start agent: ${e.message}")
            }
        }
    }

    private fun onTaskCancelled(cancel: WsMessage.TaskCancel) {
        val task = currentTask ?: return
        if (task.taskId != cancel.taskId) return
        cancelledTaskId = cancel.taskId
        GeaiAgentService.getInstance(project).stop()
        sendLog(task.taskId, "── Cancelled by hub: ${cancel.reason} ──")
    }


    private fun buildTaskPrompt(task: WsMessage.TaskAssign): String = buildString {
        appendLine("You are executing an orchestrated task from the geai Hub (multi-IDE fullstack pipeline).")
        appendLine()
        appendLine("## Task ${task.taskId}: ${task.taskName}")
        appendLine(task.description.ifBlank { task.taskName })

        task.metadata["reworkReason"]?.let { reason ->
            appendLine()
            appendLine("## REWORK — this task was returned with the following feedback; address it first:")
            appendLine(reason)
        }

        if (task.upstream.isNotEmpty()) {
            appendLine()
            appendLine("## Results of upstream tasks (already completed)")
            task.upstream.forEach { up ->
                appendLine()
                appendLine("### ${up.taskId}: ${up.name}")
                if (up.artifacts.isNotEmpty()) appendLine("Artifacts: ${up.artifacts.joinToString()}")
                if (up.summary.isNotBlank()) {
                    appendLine("Result:")
                    appendLine(up.summary.take(4000))
                }
            }
        }

        if (task.contracts.isNotEmpty()) {
            appendLine()
            appendLine("## Contracts you must follow")
            task.contracts.forEach { contract ->
                appendLine()
                appendLine("### ${contract.name} (${contract.version})")
                appendLine("```")
                appendLine(contract.content.take(8_000))
                appendLine("```")
            }
            appendLine()
            appendLine(
                "If a contract above is UNUSABLE for this task (missing endpoints/fields, wrong types) — " +
                    "do NOT build on top of it and do NOT try to fix it yourself. Instead reply with ONLY " +
                    "this fenced JSON block and stop:"
            )
            appendLine("```json")
            appendLine("""{"contract_rejection": {"name": "<contract file name>", "reason": "<what is wrong>", "requestedChanges": ["<change 1>", "<change 2>"]}}""")
            appendLine("```")
        }

        appendLine()
        appendLine(
            "Work autonomously in this project; do not ask the user questions unless truly blocked. " +
                "When done, summarize what was created or changed."
        )
    }

    private fun buildPlanPrompt(task: WsMessage.TaskAssign): String = buildString {
        appendLine("You are the PLANNER for the geai Hub — a multi-IDE orchestrator. Decompose the objective")
        appendLine("below into a small task graph that will be distributed across the connected agents.")
        appendLine()
        appendLine("## Objective")
        appendLine(task.description)
        appendLine()
        appendLine("## Connected agents (the fleet)")
        appendLine(task.metadata["fleet"] ?: "[]")
        appendLine()
        appendLine("Rules:")
        appendLine("- 3 to 8 tasks. Prefer contract-first: an API contract task before dependent implementation tasks.")
        appendLine("- role must be one of: backend, frontend, mobile, any. Only use roles the fleet can serve; use \"any\" otherwise.")
        appendLine("- dependsOn lists ZERO-BASED INDICES of prerequisite tasks in your array.")
        appendLine("- Set \"verify\": true for implementation tasks whose result can be checked by running tests/build.")
        appendLine("- If a task must produce an API contract, its description must say to save it as openapi.yaml;")
        appendLine("  tasks consuming that contract must list it in \"requiredContracts\": [\"openapi.yaml\"] —")
        appendLine("  the hub then delivers the contract content to them automatically.")
        appendLine("- You may inspect THIS project (read-only) to ground the plan, but do NOT modify any files.")
        appendLine("- Each description must be a self-contained instruction for an autonomous coding agent.")
        appendLine()
        appendLine("Finish your reply with ONLY this fenced JSON block (no other trailing text):")
        appendLine("```json")
        appendLine("""[{"name": "…", "description": "…", "role": "backend", "dependsOn": [], "verify": false, "requiredContracts": []}]""")
        appendLine("```")
    }

    private fun buildVerifyPrompt(task: WsMessage.TaskAssign): String = buildString {
        appendLine("You are the VERIFIER for the geai Hub. Check the completed work described below by")
        appendLine("actually running the relevant tests or build in this project (use run_command).")
        appendLine()
        appendLine(task.description)
        appendLine()
        appendLine("Rules:")
        appendLine("- Run the narrowest meaningful check first (module tests, affected test classes, or the build).")
        appendLine("- Do NOT fix or modify any code — verification only.")
        appendLine("- If no tests exist for this work, check compilation/build and say so in the summary.")
        appendLine()
        appendLine("Finish your reply with ONLY this fenced JSON block:")
        appendLine("```json")
        appendLine("""{"passed": true, "summary": "<what was run and the outcome, 1-3 sentences>"}""")
        appendLine("```")
    }

    private fun buildMapPrompt(): String = buildString {
        appendLine("You are the MAPPER for the geai Hub. Build an architecture map of THIS project for a")
        appendLine("cross-project graph. Use the IDE's structure/search tools (graph_query, search_text,")
        appendLine("find_files, read_file, project_overview) — read-only, do NOT modify anything.")
        appendLine()
        appendLine("GROUND the map mechanically in the IDE index FIRST — run these searches and build nodes")
        appendLine("ONLY from what they return (never invent):")
        appendLine("- APIs: search_text \"@RestController\", \"@RequestMapping\", \"@GetMapping\", \"@PostMapping\", \"@GrpcService\";")
        appendLine("- messaging: search_text \"@KafkaListener\", \"KafkaTemplate\", \"@RabbitListener\", \"topics\" and topic")
        appendLine("  names in application.yml / application.properties / *.yaml configs;")
        appendLine("- stores: search_text \"@Entity\", \"@Table\", \"jdbc:\", \"datasource\", \"@Repository\";")
        appendLine("- outbound: search_text \"RestTemplate\", \"WebClient\", \"@FeignClient\", \"HttpClient\".")
        appendLine("Then use graph_query/read_file to confirm names and pick the one main source anchor per node.")
        appendLine()
        appendLine("Find and map:")
        appendLine("- the service itself and its major modules;")
        appendLine("- exposed APIs: REST controllers/endpoints, gRPC services (node kind \"api\", list operations in \"api\");")
        appendLine("- messaging: Kafka/AMQP topics produced or consumed (kind \"topic\", label = exact topic name);")
        appendLine("- data stores: databases/schemas (kind \"db\", label = db/schema name);")
        appendLine("- outbound calls to OTHER services (kind \"external\", label = target service/endpoint).")
        appendLine()
        appendLine("Keep it readable: 5–15 nodes. Every node needs a one-sentence description; \"file\" is the")
        appendLine("main source anchor (project-relative). Edges connect YOUR nodes (from/to = node ids).")
        appendLine()
        appendLine("CRITICAL for cross-service stitching (the hub merges maps from several projects):")
        appendLine("- topic/db node labels must be the EXACT literal names from code/config (e.g. \"cotc_upoa_transaction\"),")
        appendLine("  never paraphrased — identical names are how the hub connects services;")
        appendLine("- \"external\" node labels must be the exact target service/artifact name (e.g. \"upoa-dbms-service\");")
        appendLine("- EVERY edge needs a short \"label\": for kafka — \"produces\"/\"consumes\" + what; for rest — the endpoint;")
        appendLine("  for db — the schema/table group. Unlabeled edges are useless on the map.")
        appendLine()
        appendLine("Finish your reply with ONLY this fenced JSON block:")
        appendLine("```json")
        appendLine("""{"project": "<project name>", "nodes": [{"id": "svc", "label": "…", "kind": "service|module|api|topic|db|external", "description": "…", "api": ["GET /…"], "file": "src/…"}], "edges": [{"from": "svc", "to": "api1", "label": "…", "kind": "rest|kafka|db|lib|calls"}]}""")
        appendLine("```")
    }


    private inner class HubTaskListener(private val task: WsMessage.TaskAssign) : AgentListener {
        private var toolCalls = 0
        private val artifacts = LinkedHashSet<String>()
        private var lastAssistantText = ""

        override fun onEvent(event: AgentEvent) {
            try {
                when (event) {
                    is AgentEvent.ToolStarted -> {
                        toolCalls++
                        sendProgress(percent(), "→ ${event.tool}")
                        sendLog(task.taskId, "→ ${event.tool} ${summarizeArgs(event.argsJson)}")
                        extractPath(event.tool, event.argsJson)?.let { artifacts.add(it) }
                    }
                    is AgentEvent.ToolFinished -> {
                        val mark = if (event.result.isError) "✗" else "✓"
                        sendLog(task.taskId, "$mark ${event.tool}")
                        sendProgress(percent(), "$mark ${event.tool}")
                    }
                    is AgentEvent.AssistantText -> {
                        lastAssistantText = event.text
                        event.text.lines().filter { it.isNotBlank() }.take(8).forEach { line ->
                            sendLog(task.taskId, line.take(300))
                        }
                    }
                    is AgentEvent.Info -> sendLog(task.taskId, "ℹ ${event.text.take(300)}")
                    is AgentEvent.Done -> onDone(event)
                    is AgentEvent.Error -> finishTask(task, "failure", errorMessage = event.text.take(500))
                    is AgentEvent.Cancelled -> finishTask(task, "cancelled", errorMessage = event.text)
                    is AgentEvent.UserMessage, AgentEvent.Thinking, is AgentEvent.Reasoning,
                    is AgentEvent.ReasoningDelta, is AgentEvent.AssistantTextDelta -> Unit
                }
            } catch (e: Exception) {
                log.warn("Hub listener error", e)
            }
        }

        private fun onDone(event: AgentEvent.Done) {
            val tokensIn = event.usage.inputTokens.toLong()
            val tokensOut = event.usage.outputTokens.toLong()
            when (kindOf(task)) {
                "plan", "verify", "map" -> {
                    val json = extractJsonBlock(lastAssistantText)
                    if (json == null) {
                        finishTask(
                            task, "failure",
                            errorMessage = "No JSON block in the ${kindOf(task)} reply",
                            tokensIn = tokensIn, tokensOut = tokensOut
                        )
                    } else {
                        finishTask(task, "success", outputLog = json, tokensIn = tokensIn, tokensOut = tokensOut)
                    }
                }
                else -> {
                    val rejection = detectContractRejection(lastAssistantText)
                    if (rejection != null && task.contracts.isNotEmpty()) {
                        client?.send(rejection)
                        sendLog(task.taskId, "⇧ Contract rejected: ${rejection.name} — ${rejection.reason.take(200)}")
                        finishTask(
                            task, "requeue",
                            errorMessage = "Waiting for a new version of contract '${rejection.name}'",
                            tokensIn = tokensIn, tokensOut = tokensOut
                        )
                    } else {
                        publishOpenApiContracts(artifacts.toList())
                        val result = buildString {
                            append(lastAssistantText.take(12_000))
                            if (lastAssistantText.length > 12_000) append("\n… [truncated]")
                        }
                        finishTask(
                            task, "success",
                            artifacts = artifacts.toList(),
                            outputLog = result,
                            tokensIn = tokensIn, tokensOut = tokensOut
                        )
                    }
                }
            }
        }

        private fun percent(): Int = (5 + toolCalls * 6).coerceAtMost(90)

        private fun sendProgress(pct: Int, message: String) {
            client?.send(
                WsMessage.AgentProgress(
                    spokeId = spokeId,
                    taskId = task.taskId,
                    agentName = agentName,
                    percent = pct,
                    message = message.take(200)
                )
            )
        }

        private fun summarizeArgs(raw: String): String = try {
            val obj = lenientJson.parseToJsonElement(raw).jsonObject
            obj.entries.joinToString(", ", prefix = "(", postfix = ")") { (k, v) ->
                "$k=${v.toString().take(60)}"
            }.take(160)
        } catch (_: Exception) {
            ""
        }

        private fun extractPath(tool: String, raw: String): String? {
            if (tool != "write_file" && tool != "edit_file") return null
            return try {
                lenientJson.parseToJsonElement(raw).jsonObject["path"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }
    }


    /**
     * Pull one JSON value out of a possibly-chatty reply. Prefers the last fenced ```json block,
     * else scans the whole text; in BOTH cases it returns a BRACE-BALANCED span (string/escape
     * aware), so nested JSON is not truncated at the first inner `}` (the old lazy-regex bug) and
     * trailing prose after the value does not break parsing.
     */
    private fun extractJsonBlock(text: String): String? {
        val fenceRegex = Regex("```(?:json)?\\s*(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val fenced = fenceRegex.findAll(text).lastOrNull()?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        return (fenced?.let { balancedJsonSpan(it) }) ?: balancedJsonSpan(text)
    }

    /** First `{`/`[` in [s] to its matching close, tracking depth outside string literals. */
    private fun balancedJsonSpan(s: String): String? {
        val start = s.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val open = s[start]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else when (c) {
                '"' -> inString = true
                open -> depth++
                close -> if (--depth == 0) return s.substring(start, i + 1)
            }
        }
        return null
    }

    private fun detectContractRejection(text: String): WsMessage.ContractReject? {
        val raw = extractJsonBlock(text) ?: return null
        return try {
            val obj = lenientJson.parseToJsonElement(raw).jsonObject["contract_rejection"]?.jsonObject ?: return null
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: return null
            WsMessage.ContractReject(
                spokeId = spokeId,
                taskId = currentTask?.taskId ?: "",
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                contractId = obj["contractId"]?.jsonPrimitive?.contentOrNull ?: "",
                reason = reason.take(600),
                requestedChanges = obj["requestedChanges"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull?.take(300) }
                    ?.take(10)
                    .orEmpty()
            )
        } catch (_: Exception) {
            null
        }
    }


    private fun finishTask(
        task: WsMessage.TaskAssign,
        outcome: String,
        artifacts: List<String> = emptyList(),
        outputLog: String = "",
        errorMessage: String = "",
        tokensIn: Long = 0,
        tokensOut: Long = 0
    ) {
        currentTask = null
        client?.send(
            WsMessage.TaskResult(
                spokeId = spokeId,
                taskId = task.taskId,
                agentName = agentName,
                outcome = outcome,
                artifacts = artifacts,
                outputLog = outputLog,
                errorMessage = errorMessage,
                inputTokens = tokensIn,
                outputTokens = tokensOut
            )
        )
        announceReadyWhenIdle(attemptsLeft = 20)
        if (!isSynthetic(task)) {
            val type = when (outcome) {
                "success", "cancelled", "requeue" -> NotificationType.INFORMATION
                else -> NotificationType.WARNING
            }
            notify("Hub task ${if (outcome == "success") "completed" else outcome}", "${task.taskId}: ${task.taskName}", type)
        }
    }

    private fun announceReadyWhenIdle(attemptsLeft: Int) {
        if (currentTask != null || client == null) return
        val busy = try {
            GeaiAgentService.getInstance(project).isRunning()
        } catch (_: Exception) {
            false
        }
        if (!busy || attemptsLeft <= 0) {
            sendStatus("ready", null, 0, "")
        } else {
            com.intellij.util.concurrency.AppExecutorUtil.getAppScheduledExecutorService()
                .schedule({ announceReadyWhenIdle(attemptsLeft - 1) }, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun publishOpenApiContracts(artifacts: List<String>) {
        artifacts.filter { path ->
            val lower = path.lowercase()
            lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".json")
        }.filter { path ->
            val name = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
            "openapi" in name || "swagger" in name || "api-contract" in name
        }.forEach { path ->
            try {
                val file = resolve(path) ?: return@forEach
                if (!file.exists() || file.length() > 200_000) return@forEach
                val name = file.name
                client?.send(
                    WsMessage.ContractPublish(
                        spokeId = spokeId,
                        contractId = "$spokeId-$name",
                        name = name,
                        version = "1.0.${System.currentTimeMillis() % 1000}",
                        content = file.readText(),
                        mimeType = if (name.endsWith(".json")) "application/json" else "application/x-yaml",
                        taskId = currentTask?.taskId ?: ""
                    )
                )
                sendLog(currentTask?.taskId ?: "", "⇧ Published contract: $name")
            } catch (e: Exception) {
                log.debug("Contract publish skipped for $path: ${e.message}")
            }
        }
    }

    private fun resolve(path: String): File? {
        val f = File(path)
        if (f.isAbsolute) return f
        val base = project.basePath ?: return null
        return File(base, path)
    }


    private fun sendStatus(state: String, taskId: String?, percent: Int, message: String) {
        client?.send(
            WsMessage.SpokeStatus(
                spokeId = spokeId,
                agentName = agentName,
                state = state,
                currentTaskId = taskId,
                progressPercent = percent,
                statusMessage = message
            )
        )
    }

    private fun sendLog(taskId: String, line: String) {
        if (taskId.isBlank()) return
        client?.send(WsMessage.TaskLog(spokeId = spokeId, taskId = taskId, line = line))
    }

    private fun notify(title: String, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Geai Hub")
                .createNotification(title, content, type)
                .notify(project)
        } catch (e: Exception) {
            log.debug("Notification failed: ${e.message}")
        }
    }
}
