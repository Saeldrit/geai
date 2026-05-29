package com.github.saeldrit.geai.tools.selfmod

import com.github.saeldrit.geai.settings.GeaiSettings
import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/** Writes a file inside geai's own source tree, scoped to the configured source root. */
object SelfPatchTool : AgentTool {
    override val name = "self_patch"
    override val mutating = true
    override val description =
        "Create or overwrite a file inside geai's own source tree (path relative to the configured Geai " +
            "source root). Use to add a new tool or change geai's behavior, then rebuild with run_command " +
            "and ask the user to reload. Paths that escape the source root are rejected."
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "relative_path":{"type":"string","description":"Path relative to the Geai source root, e.g. src/main/kotlin/.../tools/MyTool.kt"},
          "content":{"type":"string","description":"Full file content"}
        },"required":["relative_path","content"]}
    """.trimIndent()

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val source = GeaiSettings.getInstance().state.geaiSourcePath?.takeIf { it.isNotBlank() }
            ?: return ToolResult.error("Geai source path is not configured (Settings | Tools | Geai).")
        val relativePath = args.string("relative_path")
        val content = args.string("content")

        return runCatching {
            val root = Paths.get(source).toAbsolutePath().normalize()
            val target = root.resolve(relativePath).normalize()
            if (!target.startsWith(root)) {
                return@runCatching ToolResult.error("'relative_path' escapes the source root: $relativePath")
            }
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, content, StandardCharsets.UTF_8)
            ToolResult.ok(
                "Patched ${root.relativize(target)} (${content.length} chars). Next: run_command " +
                    "\"gradlew.bat buildPlugin\" in $source, then ask the user to reload the plugin / restart the " +
                    "sandbox IDE to activate the change.",
            )
        }.getOrElse { ToolResult.error("self_patch failed: ${it.message}") }
    }
}
