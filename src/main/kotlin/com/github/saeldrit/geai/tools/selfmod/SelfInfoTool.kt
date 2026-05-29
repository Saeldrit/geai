package com.github.saeldrit.geai.tools.selfmod

import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult

/** Orients the agent for self-modification: where its source is and how to grow a new capability. */
object SelfInfoTool : AgentTool {
    override val name = "self_info"
    override val description =
        "Report geai's own plugin source location and the protocol for extending itself (adding a tool, " +
            "editing the system prompt) and rebuilding. Call this when you lack a capability and must grow one."
    override val parametersJsonSchema = """{"type":"object","properties":{}}"""

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val source = GeaiSettings.getInstance().state.geaiSourcePath?.takeIf { it.isNotBlank() }
            ?: return ToolResult.ok(
                "Geai's source path is not configured, so self-modification is disabled. Ask the user to set " +
                    "\"Geai source path\" in Settings | Tools | Geai. (I can still read/edit files in the open project.)",
            )
        return ToolResult.ok(
            """
            Geai source root: $source

            Self-extension protocol (the "stop-the-world, patch myself" loop):
            1. Inspect: read_file on these key files (relative to the source root):
               - src/main/kotlin/com/github/saeldrit/geai/tools/AgentTool.kt  (the tool contract)
               - src/main/kotlin/com/github/saeldrit/geai/tools/GeaiToolset.kt (the tool registry)
               - src/main/kotlin/com/github/saeldrit/geai/agent/SystemPrompt.kt (the doctrine)
            2. Patch: use self_patch to add a new `object MyTool : AgentTool { ... }` under tools/, then
               self_patch GeaiToolset.kt to register it in all().
            3. Rebuild: run_command "gradlew.bat buildPlugin" with working_dir = the source root.
            4. Reload: a running plugin cannot hot-swap its own classes — tell the user to restart the
               sandbox IDE (or reinstall the built plugin from build/distributions), then ask me to continue.
               State clearly which new capability will be available after the reload.
            """.trimIndent(),
        )
    }
}
