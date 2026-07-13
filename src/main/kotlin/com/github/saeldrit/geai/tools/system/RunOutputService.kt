package com.github.saeldrit.geai.tools.system

import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.text.SimpleDateFormat
import java.util.Date

@Service(Service.Level.PROJECT)
class RunOutputService {

    class ProcessRecord(
        val name: String,
        val executorId: String,
        val startedAtMs: Long,
    ) {
        private val buffer = StringBuilder()

        @Volatile
        var exitCode: Int? = null

        @Synchronized
        fun append(text: String) {
            buffer.append(text)
            if (buffer.length > MAX_CHARS_PER_PROCESS) {
                buffer.delete(0, buffer.length - MAX_CHARS_PER_PROCESS)
            }
        }

        @Synchronized
        fun tail(chars: Int): String {
            val start = (buffer.length - chars).coerceAtLeast(0)
            return buffer.substring(start)
        }

        @Synchronized
        fun size(): Int = buffer.length
    }

    private val records = ArrayDeque<ProcessRecord>()

    fun attach(executorId: String, env: ExecutionEnvironment, handler: com.intellij.execution.process.ProcessHandler) {
        val record = ProcessRecord(env.runProfile.name, executorId, System.currentTimeMillis())
        synchronized(records) {
            records.addLast(record)
            while (records.size > MAX_PROCESSES) records.removeFirst()
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = if (ProcessOutputType.isStderr(outputType)) prefixStderr(event.text) else event.text
                record.append(text)
            }

            override fun processTerminated(event: ProcessEvent) {
                record.exitCode = event.exitCode
                record.append("\n[process exited with code ${event.exitCode}]\n")
            }
        })
    }

    fun list(): List<ProcessRecord> = synchronized(records) { records.toList() }

    /** Newest record whose run-configuration name contains [name] (case-insensitive); null name = newest overall. */
    fun find(name: String?): ProcessRecord? = synchronized(records) {
        if (name.isNullOrBlank()) {
            records.lastOrNull()
        } else {
            records.lastOrNull { it.name.contains(name, ignoreCase = true) }
        }
    }

    companion object {
        fun getInstance(project: Project): RunOutputService = project.service()

        private const val MAX_PROCESSES = 5
        private const val MAX_CHARS_PER_PROCESS = 200_000

        private fun prefixStderr(text: String): String =
            text.lineSequence().joinToString("\n") { if (it.isBlank()) it else "[err] $it" }
                .let { if (text.endsWith("\n") && !it.endsWith("\n")) it + "\n" else it }

        internal val TIME = SimpleDateFormat("HH:mm:ss")
        internal fun stamp(ms: Long): String = TIME.format(Date(ms))
    }
}

/** Declarative project listener (plugin.xml) — capturing starts with the process, no gaps. */
class RunOutputExecutionListener : ExecutionListener {
    override fun processStarted(
        executorId: String,
        env: ExecutionEnvironment,
        handler: com.intellij.execution.process.ProcessHandler,
    ) {
        RunOutputService.getInstance(env.project).attach(executorId, env, handler)
    }
}
