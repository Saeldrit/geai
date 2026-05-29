package com.github.saeldrit.geai.tools.fs

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

internal object Globs {
    /** Converts a simple glob (`*`, `?`) into a case-insensitive anchored regex. */
    fun toRegex(glob: String): Regex {
        val escaped = glob.trim().replace(".", "\\.").replace("*", ".*").replace("?", ".")
        return Regex("^$escaped$", RegexOption.IGNORE_CASE)
    }
}

/** Prefer the in-memory document (reflects the agent's unsaved edits) over on-disk content. */
internal fun readTextOrNull(file: VirtualFile): String? {
    FileDocumentManager.getInstance().getCachedDocument(file)?.let { return it.charsSequence.toString() }
    return runCatching { VfsUtilCore.loadText(file) }.getOrNull()
}
