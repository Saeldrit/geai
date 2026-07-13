package com.github.saeldrit.geai.tools.debug

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

object FailureAutopsyTool : AgentTool {
    override val name = "failure_autopsy"
    override val description =
        "Structured post-mortem of the CURRENT pause in ONE call: paused location, the call stack, " +
            "and local variables of the top frames. Use it immediately after break_on_exception " +
            "fires or a crash-site breakpoint hits — frame #0 is the throw/failure site; walk the " +
            "locals down the stack to see what state produced it. Replaces a whole " +
            "debug_state + stack + N×debug_variables round-trip chain."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "frames":{"type":"integer","description":"How many top frames to include locals for (default 3, max 8)"},
          "vars_per_frame":{"type":"integer","description":"Max locals per frame (default 12, max 30)"}
        }}
    """.trimIndent()

    private const val STACK_LIST_LIMIT = 15
    private const val TIMEOUT_MS = 4_000L

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val framesWanted = args.int("frames", 3).coerceIn(1, 8)
        val varsPerFrame = args.int("vars_per_frame", 12).coerceIn(3, 30)
        val project = context.project

        val where = DebuggerSupport.pausedSessionDescription(project)
            ?: return ToolResult.error(
                "Debugger is not paused — nothing to autopsy. Typical flow: break_on_exception " +
                    "(or set_breakpoint at the crash site), trigger the failure, await_pause, then failure_autopsy.",
            )

        val frames = FrameInspection.topFrames(project, STACK_LIST_LIMIT, TIMEOUT_MS)
        val builder = StringBuilder()
        builder.appendLine("── FAILURE AUTOPSY ──")
        builder.appendLine(where)
        builder.appendLine()

        if (frames.isEmpty()) {
            builder.appendLine("(could not compute the execution stack — inspect with debug_variables/debug_evaluate)")
            return ToolResult.ok(builder.toString().trimEnd())
        }

        builder.appendLine("Call stack (top ${frames.size}):")
        frames.forEachIndexed { i, frame ->
            builder.appendLine("  #$i ${FrameInspection.framePosition(project, frame)}")
        }
        builder.appendLine()

        frames.take(framesWanted).forEachIndexed { i, frame ->
            if (context.indicator.isCanceled) return@forEachIndexed
            builder.appendLine("Frame #$i — ${FrameInspection.framePosition(project, frame)}:")
            builder.appendLine(FrameInspection.localsOf(frame, TIMEOUT_MS, varsPerFrame))
            builder.appendLine()
        }

        builder.append(
            "Next: frame #0 is the failure site — read that code around the line, check which local " +
                "above is wrong, and use debug_evaluate for anything specific. Remember to disable " +
                "break_on_exception when done.",
        )
        return ToolResult.ok(builder.toString().trimEnd())
    }
}
