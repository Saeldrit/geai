package com.github.saeldrit.geai.anchor.resolvers

import com.github.saeldrit.geai.anchor.AnchorException
import com.github.saeldrit.geai.anchor.AnchorResolver
import com.github.saeldrit.geai.anchor.ResolvedAnchor
import com.github.saeldrit.geai.tools.fs.FsPaths
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * Resolves `file:<path>[:start-end]` into a (optionally line-ranged) slice of a project file.
 * The trailing `:N` / `:N-M` is parsed off the end so absolute paths with a drive colon
 * (`C:\...`) still work.
 */
object FileAnchorResolver : AnchorResolver {

    override val scheme = "file"

    private val RANGE = Regex(":(\\d+)(?:-(\\d+))?$")
    private const val MAX_BYTES = 2_000_000L

    override fun resolve(locator: String, project: Project): ResolvedAnchor {
        val match = RANGE.find(locator)
        val path = if (match != null) locator.substring(0, match.range.first) else locator
        val start = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val end = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: start

        return ReadAction.compute<ResolvedAnchor, RuntimeException> {
            val file = FsPaths.resolve(project, path)
                ?: throw AnchorException("file: not found: $path")
            if (file.isDirectory) throw AnchorException("file: is a directory, not a file: $path")
            if (file.length > MAX_BYTES) throw AnchorException("file: too large (${file.length} bytes): $path")
            if (file.fileType.isBinary) throw AnchorException("file: refusing to resolve binary file: $path")

            val document = FileDocumentManager.getInstance().getDocument(file)
                ?: throw AnchorException("file: cannot load content: $path")
            val lines = document.charsSequence.toString().split("\n")
            val from = (start ?: 1).coerceIn(1, lines.size)
            val to = (end ?: lines.size).coerceIn(from, lines.size)
            val body = (from..to).joinToString("\n") { i -> "$i\t${lines[i - 1]}" }
            val rel = FsPaths.relativize(project, file)
            ResolvedAnchor.of(
                ref = "file:$locator",
                kind = "file",
                location = "$rel:$from-$to",
                signature = null,
                content = body,
            )
        }
    }
}
