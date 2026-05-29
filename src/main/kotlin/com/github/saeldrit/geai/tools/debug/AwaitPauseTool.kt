package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.progress.ProcessCanceledException

/** Blocks (cooperatively) until a debug session pauses at a breakpoint or the timeout elapses. */
object AwaitPauseTool : AgentTool {
    override val name = "await_pause"
    override val description =
        "Wait until a debug session pauses at a breakpoint (or timeout). Returns the paused location " +
            "so you can read that code and reason about the runtime state that reached it."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait (default 30, max 300)"}
        }}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val timeoutSeconds = args.int("timeout_seconds", 30).coerceIn(1, 300)
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            if (context.indicator.isCanceled) throw ProcessCanceledException()
            DebuggerSupport.pausedSessionDescription(context.project)?.let {
                return ToolResult.ok("Paused — $it")
            }
            try {
                Thread.sleep(250)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ProcessCanceledException()
            }
        }
        return ToolResult.ok(
            "No breakpoint hit within ${timeoutSeconds}s. The program may still be running, may not " +
                "reach the breakpoint, or already finished. Check debug_state.",
        )
    }
}
