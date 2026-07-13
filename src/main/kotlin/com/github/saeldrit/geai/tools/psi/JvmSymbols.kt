package com.github.saeldrit.geai.tools.psi

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * Small JVM-PSI helpers shared by the PSI-backed navigation tools. Works for Java and Kotlin (both
 * expose JVM light-PSI). On a non-JVM IDE (no Java plugin) PSI access throws [JavaUnavailable], which
 * the tools translate into a clear message instead of crashing the loop.
 */
object JvmSymbols {

    class JavaUnavailable : RuntimeException(
        "Java PSI (com.intellij.modules.java) is not available in this IDE — use search_text / read_file instead.",
    )

    fun resolve(project: Project, fqName: String, member: String?): PsiElement? {
        val psiClass = findClass(project, fqName) ?: return null
        return if (member == null) psiClass else findMember(psiClass, member)
    }

    fun findClass(project: Project, fqName: String): PsiClass? = try {
        JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.allScope(project))
    } catch (_: NoClassDefFoundError) {
        throw JavaUnavailable()
    } catch (_: ClassNotFoundException) {
        throw JavaUnavailable()
    }

    fun findMember(psiClass: PsiClass, member: String): PsiElement? =
        psiClass.findMethodsByName(member, true).firstOrNull()
            ?: psiClass.findFieldByName(member, true)
            ?: psiClass.innerClasses.firstOrNull { it.name == member }

    fun locationOf(element: PsiElement): String? {
        val vf = element.containingFile?.virtualFile ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return vf.path
        return "${vf.path}:${doc.getLineNumber(element.textOffset) + 1}"
    }

    /** Name of the nearest enclosing method (Class#method) or class — context for a usage site. */
    fun enclosingName(element: PsiElement): String? {
        PsiTreeUtil.getParentOfType(element, PsiMethod::class.java)?.let { method ->
            val owner = PsiTreeUtil.getParentOfType(method, PsiClass::class.java)?.name
            return if (owner != null) "$owner#${method.name}" else method.name
        }
        return PsiTreeUtil.getParentOfType(element, PsiClass::class.java)?.name
    }

    /** The trimmed source line containing [element], capped — a readable snippet for a usage row. */
    fun lineSnippet(element: PsiElement): String {
        val vf = element.containingFile?.virtualFile ?: return ""
        val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return ""
        val line = doc.getLineNumber(element.textOffset)
        return doc.getText(TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim().take(160)
    }
}
