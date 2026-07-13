package com.github.saeldrit.geai.tools.fs

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

internal object PostEditCheck {

    private const val MAX_ERRORS_SHOWN = 5

    fun syntaxWarning(project: Project, file: VirtualFile, document: Document?): String = runCatching {
        if (document != null) PsiDocumentManager.getInstance(project).commitDocument(document)
        val psi = PsiManager.getInstance(project).findFile(file) ?: return ""
        val errors = PsiTreeUtil.collectElementsOfType(psi, PsiErrorElement::class.java)
        if (errors.isEmpty()) return ""
        buildString {
            append("\n⚠ SYNTAX ERRORS after this change — fix them before doing anything else:")
            errors.take(MAX_ERRORS_SHOWN).forEach { err ->
                val line = document?.getLineNumber(err.textOffset.coerceIn(0, (document.textLength - 1).coerceAtLeast(0)))?.plus(1) ?: 0
                append("\n  line $line: ${err.errorDescription.take(140)}")
            }
            if (errors.size > MAX_ERRORS_SHOWN) append("\n  … (${errors.size - MAX_ERRORS_SHOWN} more — run diagnostics for the full list)")
        }
    }.getOrDefault("")
}
