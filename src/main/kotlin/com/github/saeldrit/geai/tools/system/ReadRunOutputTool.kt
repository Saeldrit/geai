package com.github.saeldrit.geai.tools.system

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object ReadRunOutputTool : AgentTool {
    override val name = "read_run_output"
    override val idempotentPoll = true
    override val description =
        "Read the console output (stdout/stderr, stderr lines prefixed [err]) of Run/Debug " +
            "configurations launched in this IDE — the app's prints, stack traces, and framework " +
            "logs. Use it to see what the program ACTUALLY printed when reproducing a bug, instead " +
            "of guessing: run the scenario (or ask the user to), then read the tail. Repeated calls " +
            "while the app runs are fine (poll). Without 'name' returns the most recent process. " +
            "For Android logcat use run_command: adb logcat -d -t 300."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "name":{"type":"string","description":"Substring of the run-configuration name to select (optional; default = most recent)"},
          "tail_chars":{"type":"integer","description":"How many characters of the tail to return (default 4000, max 20000)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val name = args.stringOrNull("name")
        val tailChars = args.int("tail_chars", 4_000).coerceIn(200, 20_000)
        val service = RunOutputService.getInstance(context.project)

        val all = service.list()
        if (all.isEmpty()) {
            return ToolResult.ok(
                "No Run/Debug output captured yet. Output capture starts when a run configuration is " +
                    "launched (Run/Debug in the IDE, or start_debug). If the process was started outside " +
                    "the IDE, use run_command to inspect it instead.",
            )
        }

        val record = service.find(name)
            ?: return ToolResult.error(
                "No captured process matches '$name'. Captured: " +
                    all.joinToString(", ") { "'${it.name}'" },
            )

        val header = all.reversed().joinToString("\n") { r ->
            val state = r.exitCode?.let { "exited $it" } ?: "running"
            val marker = if (r === record) "→" else " "
            "$marker ${r.name} [${r.executorId}] started ${RunOutputService.stamp(r.startedAtMs)} ($state, ${r.size()} chars captured)"
        }
        val tail = record.tail(tailChars)
        val body = if (tail.isBlank()) "(no output captured yet from this process)" else tail
        return ToolResult.ok("$header\n\n--- output tail of '${record.name}' (last ${tail.length} chars) ---\n$body")
    }
}
