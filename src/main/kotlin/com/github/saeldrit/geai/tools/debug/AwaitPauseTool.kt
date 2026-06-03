package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.openapi.progress.ProcessCanceledException

/** Blocks (cooperatively) until a debug session pauses at a breakpoint or the timeout elapses. */
object AwaitPauseTool : AgentTool {
    override val name = "await_pause"
    override val idempotentPoll = true
    override val description =
        "Wait until a debug session pauses at a breakpoint (or timeout). Returns the paused location " +
            "so you can read that code and reason about the runtime state that reached it. Returns " +
            "immediately if already paused. When you need the USER to trigger the request, use a LONG " +
            "timeout (e.g. 300) and wait here — do NOT end your turn telling the user to go and then stop."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "timeout_seconds":{"type":"integer","description":"Max seconds to wait (default 60, max 600). Use 300+ when waiting for the user to trigger the request."}
        }}
    """.trimIndent()

    /** Grace for a session to appear after start_debug before we treat "no session" as "nothing to wait for". */
    private const val SESSION_GRACE_MS = 4_000L

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val timeoutSeconds = args.int("timeout_seconds", 60).coerceIn(1, 600)
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        val graceDeadline = System.currentTimeMillis() + SESSION_GRACE_MS
        var sawSession = false

        while (System.currentTimeMillis() < deadline) {
            if (context.indicator.isCanceled) throw ProcessCanceledException()
            val (pausedAt, active) = DebuggerSupport.pauseSnapshot(context.project)
            if (pausedAt != null) return ToolResult.ok("Paused — $pausedAt")
            // No pause. If a session is live, keep waiting (it may hit a breakpoint). But if there is NO
            // live session — it ENDED (program finished / ran past all breakpoints) or never started —
            // don't block out the whole timeout: return now. (A short grace covers start_debug's launch.)
            if (active) {
                sawSession = true
            } else if (sawSession || System.currentTimeMillis() >= graceDeadline) {
                return ToolResult.ok(
                    "No debug session is running — it ended (the program finished or ran past every " +
                        "breakpoint) or was never started. Don't keep waiting here; check debug_state, or " +
                        "set a breakpoint and start_debug again.",
                )
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
