package com.github.saeldrit.geai.tools.system

import com.github.saeldrit.geai.tools.AgentTool
import com.github.saeldrit.geai.tools.ToolArgs
import com.github.saeldrit.geai.tools.ToolContext
import com.github.saeldrit.geai.tools.ToolResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.util.execution.ParametersListUtil
import java.io.File
import java.nio.charset.StandardCharsets

object RunCommandTool : AgentTool {
    override val name = "run_command"
    override val mutating = true
    override val description =
        "Run an external command (rebuild, run the app, run tests, git, etc.) in the project root or a " +
            "given working directory. Returns the exit code and captured stdout/stderr (tails). Use this " +
            "to reproduce, rebuild, and audit the result of a fix. " + shellHint()

    private fun shellHint(): String =
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            "Runs through Windows cmd.exe: tail/head/grep/sed/cat/ls do NOT exist — use findstr/dir/type " +
                "or one `powershell -Command` call; write multi-word git commit messages to " +
                ".git/geai-commit-msg.txt via write_file and use `git commit -F`."
        } else {
            "Runs through /bin/sh."
        }
    override val parametersJsonSchema = """
        {"type":"object","properties":{
          "command":{"type":"string","description":"Full command line, e.g. \"gradlew.bat test\" or \"git status\""},
          "working_dir":{"type":"string","description":"Working directory (absolute or project-relative). Defaults to project root."},
          "timeout_seconds":{"type":"integer","description":"Max seconds before the process is killed (default 180, max 1800)"}
        },"required":["command"]}
    """.trimIndent()

    private const val OUTPUT_TAIL = 6000

    override fun execute(args: ToolArgs, context: ToolContext): ToolResult {
        val command = args.string("command")
        val workingDir = args.stringOrNull("working_dir")
        val timeoutSeconds = args.int("timeout_seconds", 300).coerceIn(1, 1800)

        if (command.isBlank()) return ToolResult.error("Empty command.")
        val dir = resolveWorkingDir(context, workingDir)
            ?: return ToolResult.error(
                "Working directory not found, not a directory, or outside the project: ${workingDir ?: "<project root>"}",
            )

        return runCatching {
            val isWindows = System.getProperty("os.name", "").lowercase().contains("win")
            val shellParts = if (isWindows) listOf("cmd", "/c", command) else listOf("/bin/sh", "-c", command)
            val commandLine = GeneralCommandLine(shellParts)
                .withWorkDirectory(dir)
                .withCharset(StandardCharsets.UTF_8)
            val handler = CapturingProcessHandler(commandLine)
            val output = handler.runProcessWithProgressIndicator(context.indicator, timeoutSeconds * 1000)

            runCatching {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)?.let { vfsDir ->
                    VfsUtil.markDirtyAndRefresh(true, true, true, vfsDir)
                }
            }

            val sb = StringBuilder()
            sb.appendLine("$ $command   (in ${dir.path})")
            sb.append("exit code: ${output.exitCode}")
            if (output.isTimeout) sb.append(" (timed out)")
            if (output.isCancelled) sb.append(" (cancelled)")
            sb.append('\n')
            if (output.stdout.isNotBlank()) sb.append("\n--- stdout (tail) ---\n").append(tail(output.stdout))
            if (output.stderr.isNotBlank()) sb.append("\n--- stderr (tail) ---\n").append(tail(output.stderr))

            val failed = output.exitCode != 0 || output.isTimeout
            ToolResult(sb.toString().trim(), isError = failed)
        }.getOrElse { ToolResult.error("Failed to run '$command': ${it.message}") }
    }

    private fun resolveWorkingDir(context: ToolContext, dir: String?): File? = runCatching {
        val base = context.project.basePath?.let(::File)?.canonicalFile ?: return null
        if (dir.isNullOrBlank()) return base
        val candidate = File(dir).let { if (it.isAbsolute) it else File(base, dir) }.canonicalFile
        if (candidate != base && !candidate.path.startsWith(base.path + File.separator)) return null
        candidate.takeIf { it.isDirectory }
    }.getOrNull()

    private fun tail(text: String): String =
        if (text.length <= OUTPUT_TAIL) text else "…(${text.length - OUTPUT_TAIL} chars trimmed)\n" + text.takeLast(OUTPUT_TAIL)
}
